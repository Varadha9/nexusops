package com.nexusops.ai;

import com.nexusops.ai.client.AiHealth;
import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The workspace's AI opt-out and monthly limit (D9), its usage count and the index rebuild. The settings row is created
 * lazily with the defaults. ai-service is never called inside a transaction (D1).
 */
@Service
public class AiSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AiSettingsService.class);

    static final boolean DEFAULT_ENABLED = true;
    static final int DEFAULT_LIMIT = 500;
    static final int MAX_LIMIT = 100_000;
    static final String LIMIT_RULE = "Enter a limit from 1 to 100000.";
    static final String ENABLED_RULE = "Choose whether AI assistance is on or off.";
    static final String VERSION_RULE = "Reload the record and try again.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String PROVIDER_UNAVAILABLE = "UNAVAILABLE";
    private static final String LOCK_SCOPE = "ai-settings";
    /** The spec's {@code ai.settings.updated} / {@code ai.index.rebuilt}; audit actions are letters only (V4). */
    static final String SETTINGS_UPDATED = "AiSettingsUpdated";
    static final String INDEX_REBUILT = "AiIndexRebuilt";

    /** The current settings. */
    record Settings(boolean enabled, int monthlyLimit, long version) {
        Map<String, Object> snapshot() {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("enabled", enabled);
            values.put("monthlyLimit", monthlyLimit);
            return values;
        }
    }

    private final JdbcTemplate jdbc;
    private final TenantLocks locks;
    private final AuditService audit;
    private final AiClient ai;
    private final KnowledgeIndex index;
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;

    AiSettingsService(JdbcTemplate jdbc, TenantLocks locks, AuditService audit, AiClient ai, KnowledgeIndex index,
            PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.locks = locks;
        this.audit = audit;
        this.ai = ai;
        this.index = index;
        this.tx = new TransactionTemplate(transactions);
        this.readOnlyTx = new TransactionTemplate(transactions);
        this.readOnlyTx.setReadOnly(true);
    }

    /** For the UI: is AI on, and does ai-service answer? Never fails because ai-service is down. */
    public AiStatusView status() {
        return new AiStatusView(current().enabled(), provider());
    }

    public AiSettingsView view() {
        Settings settings = current();
        long used = usedThisMonth();
        long chunks = readOnlyTx.execute(s -> index.chunkCount());
        return new AiSettingsView(settings.enabled(), settings.monthlyLimit(), used, provider(), chunks,
                settings.version());
    }

    public AiSettingsView update(AiSettingsCommand command, Long version) {
        TenantContext.requireTenantId();
        if (command.monthlyLimit() == null || command.monthlyLimit() < 1 || command.monthlyLimit() > MAX_LIMIT) {
            throw ApiProblem.badRequestField("monthlyLimit", LIMIT_RULE);
        }
        if (command.enabled() == null) {
            throw ApiProblem.badRequestField("enabled", ENABLED_RULE);
        }
        if (version == null) {
            throw ApiProblem.badRequestField("version", VERSION_RULE);
        }
        tx.executeWithoutResult(s -> {
            locks.lock(LOCK_SCOPE);
            Settings before = find(true).orElseGet(this::insertDefaults);
            if (before.version() != version) {
                throw ApiProblem.conflict(STALE);
            }
            Settings after = new Settings(command.enabled(), command.monthlyLimit(), before.version() + 1);
            jdbc.update("UPDATE ai_settings SET enabled = ?, monthly_limit = ?, version = ?, updated_at = ? "
                    + "WHERE tenant_id = ?", after.enabled(), after.monthlyLimit(), after.version(),
                    Timestamp.from(Instant.now()), TenantContext.requireTenantId());
            audit.record(AuditEntry.of(SETTINGS_UPDATED, "ai_settings", TenantContext.requireTenantId())
                    .withBefore(before.snapshot()).withAfter(after.snapshot()));
        });
        return view();
    }

    /** Re-indexes every published document; 503 when ai-service is down (the old index stays). */
    public AiRebuildView rebuild() {
        TenantContext.requireTenantId();
        KnowledgeIndex.RebuildResult result;
        try {
            result = index.rebuild();
        } catch (AiUnavailableException e) {
            throw ApiProblem.serviceUnavailable(AiAssistService.UNAVAILABLE);
        }
        tx.executeWithoutResult(s -> audit.record(AuditEntry.of(INDEX_REBUILT, "ai_index",
                TenantContext.requireTenantId()).withMetadata(Map.of("indexedSources", result.indexedSources(),
                        "indexedChunks", result.indexedChunks()))));
        return new AiRebuildView(result.indexedSources(), result.indexedChunks());
    }

    /** The settings, creating the defaults on first use. */
    Settings current() {
        TenantContext.requireTenantId();
        Optional<Settings> found = readOnlyTx.execute(s -> find(false));
        if (found != null && found.isPresent()) {
            return found.get();
        }
        return tx.execute(s -> {
            locks.lock(LOCK_SCOPE);
            return find(false).orElseGet(this::insertDefaults);
        });
    }

    /** Requests recorded this calendar month (UTC). */
    long usedThisMonth() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM ai_requests WHERE tenant_id = ? AND created_at >= "
                + "date_trunc('month', now() AT TIME ZONE 'UTC') AT TIME ZONE 'UTC'", Long.class,
                TenantContext.requireTenantId());
        return count == null ? 0 : count;
    }

    /** ai-service's provider, or {@code UNAVAILABLE} when it can't be reached. */
    String provider() {
        try {
            AiHealth health = ai.health();
            return health == null || health.provider() == null ? PROVIDER_UNAVAILABLE : health.provider();
        } catch (RuntimeException e) {
            log.debug("ai-service health check failed: {}", e.toString());
            return PROVIDER_UNAVAILABLE;
        }
    }

    private Optional<Settings> find(boolean forUpdate) {
        List<Settings> rows = jdbc.query("SELECT enabled, monthly_limit, version FROM ai_settings WHERE tenant_id = ?"
                + (forUpdate ? " FOR UPDATE" : ""), (rs, n) -> new Settings(rs.getBoolean("enabled"),
                        rs.getInt("monthly_limit"), rs.getLong("version")), TenantContext.requireTenantId());
        return rows.stream().findFirst();
    }

    private Settings insertDefaults() {
        UUID tenant = TenantContext.requireTenantId();
        jdbc.update("INSERT INTO ai_settings (tenant_id, enabled, monthly_limit, updated_at, version) "
                + "VALUES (?, ?, ?, ?, 0)", tenant, DEFAULT_ENABLED, DEFAULT_LIMIT, Timestamp.from(Instant.now()));
        return new Settings(DEFAULT_ENABLED, DEFAULT_LIMIT, 0);
    }
}
