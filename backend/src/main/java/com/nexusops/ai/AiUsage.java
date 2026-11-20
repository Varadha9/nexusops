package com.nexusops.ai;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Records a completed AI request (D10): one {@code ai_requests} row and one audit event ({@link #AUDIT_ACTION}),
 * in one short transaction after the ai-service call. Never stores question or answer text.
 */
@Component
class AiUsage {

    /** What a completed request used; {@code sourceIds} are the cited documents. */
    record Completed(String feature, String mode, String model, String promptVersion, int inputTokens,
            int outputTokens, int latencyMs, List<UUID> sourceIds) {}

    /**
     * The spec's {@code ai.request.completed}; audit_events.action only accepts letters (V4), so it follows the
     * codebase's PascalCase action names.
     */
    static final String AUDIT_ACTION = "AiRequestCompleted";

    private static final String INSERT = "INSERT INTO ai_requests (id, tenant_id, user_id, feature, mode, model, "
            + "prompt_version, input_tokens, output_tokens, latency_ms, source_ids, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final TransactionTemplate tx;

    AiUsage(JdbcTemplate jdbc, AuditService audit, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.tx = new TransactionTemplate(transactions);
    }

    void record(Completed c) {
        UUID tenant = TenantContext.requireTenantId();
        UUID user = TenantContext.userId().orElse(null);
        UUID id = Ids.newId();
        tx.executeWithoutResult(s -> {
            jdbc.update(con -> {
                PreparedStatement ps = con.prepareStatement(INSERT);
                ps.setObject(1, id);
                ps.setObject(2, tenant);
                ps.setObject(3, user);
                ps.setString(4, c.feature());
                ps.setString(5, c.mode());
                ps.setString(6, c.model());
                ps.setString(7, c.promptVersion());
                ps.setInt(8, c.inputTokens());
                ps.setInt(9, c.outputTokens());
                ps.setInt(10, c.latencyMs());
                ps.setArray(11, con.createArrayOf("uuid", c.sourceIds().toArray()));
                ps.setTimestamp(12, Timestamp.from(Instant.now()));
                return ps;
            });
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("feature", c.feature());
            details.put("mode", c.mode());
            details.put("model", c.model());
            details.put("promptVersion", c.promptVersion());
            details.put("sourceIds", c.sourceIds().stream().map(UUID::toString).toList());
            details.put("latencyMs", c.latencyMs());
            audit.record(AuditEntry.of(AUDIT_ACTION, "ai_request", id).withMetadata(details));
        });
    }
}
