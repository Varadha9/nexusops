package com.nexusops.ai;

import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.knowledge.KnowledgeChanged;
import com.nexusops.shared.knowledge.KnowledgeDocument;
import com.nexusops.shared.knowledge.KnowledgeSource;
import java.sql.Array;
import java.sql.PreparedStatement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The pgvector knowledge index of PUBLISHED documents (D2, D3). A {@link KnowledgeChanged} drops the source's chunks
 * inside the publisher's transaction, so a withdrawn document is never retrievable after that commit; re-embedding
 * happens after commit and, if ai-service is down, only logs (the source stays un-indexed until its next change or a
 * rebuild). Every query runs under the tenant's RLS session.
 *
 * <p>The re-embed runs on the small {@code aiIndexExecutor} pool (tenant bound via {@link TenantContext#runAs}),
 * never on the request thread: its read, its ai-service calls and its write each use their own short connection.
 *
 * <p>Index writes take the tenant's {@code ai_index} advisory lock and re-read the source under it, so a slow
 * re-embed can't re-insert a document that was unpublished, archived or edited while it was being embedded.
 */
@Service
public class KnowledgeIndex {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndex.class);

    static final int EMBED_BATCH = 32;
    static final String LOCK_SCOPE = "ai_index";
    /** Stored on HR policy chunks; holding hr.policy.manage also satisfies it (D2). */
    static final String HR_SELF_SERVICE = "hr.self.service";
    static final String HR_POLICY_MANAGE = "hr.policy.manage";

    private static final String INSERT = "INSERT INTO ai_chunks (id, tenant_id, source_type, source_id, chunk_index, "
            + "title, content, required_permission, embedding, created_at) "
            + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::vector, ?)";
    private static final String SEARCH = """
            SELECT source_type, source_id, title, content, embedding <=> ?::vector AS distance
            FROM ai_chunks
            WHERE source_type = ANY(?) AND required_permission = ANY(?)
            ORDER BY embedding <=> ?::vector
            LIMIT ?""";

    private final JdbcTemplate jdbc;
    private final AiClient ai;
    private final TenantLocks locks;
    private final Map<String, KnowledgeSource> sources = new LinkedHashMap<>();
    private final TransactionTemplate tx;
    private final TransactionTemplate readOnlyTx;
    private final TransactionTemplate newTx;
    private final TransactionTemplate newReadOnlyTx;
    private final Executor executor;

    KnowledgeIndex(JdbcTemplate jdbc, AiClient ai, TenantLocks locks, List<KnowledgeSource> sources,
            PlatformTransactionManager transactions,
            @Qualifier(AiIndexExecutorConfig.AI_INDEX_EXECUTOR) Executor executor) {
        this.jdbc = jdbc;
        this.executor = executor;
        this.ai = ai;
        this.locks = locks;
        for (KnowledgeSource source : sources) {
            if (this.sources.put(source.sourceType(), source) != null) {
                throw new IllegalStateException("Two knowledge sources for " + source.sourceType());
            }
        }
        this.tx = new TransactionTemplate(transactions);
        this.readOnlyTx = new TransactionTemplate(transactions);
        this.readOnlyTx.setReadOnly(true);
        this.newTx = new TransactionTemplate(transactions);
        this.newTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.newReadOnlyTx = new TransactionTemplate(transactions);
        this.newReadOnlyTx.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.newReadOnlyTx.setReadOnly(true);
    }

    /** In the publisher's transaction (D3): the old chunks go with the change that withdrew or edited the source. */
    @EventListener
    public void onChanged(KnowledgeChanged event) {
        locks.lock(LOCK_SCOPE);
        delete(event.sourceType(), event.sourceId());
    }

    /**
     * After commit: hands the re-embed to {@code aiIndexExecutor}, so the request neither waits for ai-service nor
     * holds its still-checked-out connection while two more are taken. Never fails the caller.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void reindex(KnowledgeChanged event) {
        Optional<UUID> tenant = TenantContext.tenantId();
        if (tenant.isEmpty()) {
            log.warn("Not indexing {} {}: no tenant bound", event.sourceType(), event.sourceId());
            return;
        }
        try {
            executor.execute(() -> TenantContext.runAs(tenant.get(), () -> reindexNow(event)));
        } catch (RuntimeException e) {
            log.warn("Could not queue indexing of {} {}: {}", event.sourceType(), event.sourceId(), e.toString());
        }
    }

    /** Re-embeds the source if it is (still) published; on the index executor, with the tenant bound. */
    void reindexNow(KnowledgeChanged event) {
        try {
            KnowledgeSource source = source(event.sourceType());
            Optional<KnowledgeDocument> document = newReadOnlyTx.execute(s -> source.findPublished(event.sourceId()));
            if (document == null || document.isEmpty()) {
                return;
            }
            List<Chunk> chunks = embed(document.get());
            newTx.executeWithoutResult(s -> {
                locks.lock(LOCK_SCOPE);
                if (!document.equals(source.findPublished(event.sourceId()))) {
                    return; // changed or withdrawn meanwhile; that change re-indexes it
                }
                delete(event.sourceType(), event.sourceId());
                insert(chunks);
            });
        } catch (RuntimeException e) {
            log.warn("Could not index {} {}: {}", event.sourceType(), event.sourceId(), e.toString());
        }
    }

    /**
     * The closest chunks the caller may read: {@code sourceTypes} limits the kinds of document, {@code permissions}
     * are the caller's effective permissions (D2).
     */
    public List<RetrievedChunk> search(float[] query, Set<String> sourceTypes, Set<String> permissions, int limit) {
        TenantContext.requireTenantId();
        Set<String> allowed = new HashSet<>(permissions);
        if (allowed.contains(HR_POLICY_MANAGE)) {
            allowed.add(HR_SELF_SERVICE);
        }
        if (sourceTypes.isEmpty() || allowed.isEmpty() || limit <= 0) {
            return List.of();
        }
        String vector = vector(query);
        return readOnlyTx.execute(s -> {
            // pgvector >= 0.8: keep scanning the HNSW index until enough rows pass the tenant and permission filters
            jdbc.queryForObject("SELECT set_config('hnsw.iterative_scan', 'strict_order', true)", String.class);
            return jdbc.query(con -> {
                PreparedStatement ps = con.prepareStatement(SEARCH);
                Array types = con.createArrayOf("text", sourceTypes.toArray());
                Array perms = con.createArrayOf("text", allowed.toArray());
                ps.setString(1, vector);
                ps.setArray(2, types);
                ps.setArray(3, perms);
                ps.setString(4, vector);
                ps.setInt(5, limit);
                return ps;
            }, (rs, n) -> new RetrievedChunk(rs.getString("source_type"), rs.getObject("source_id", UUID.class),
                    rs.getString("title"), rs.getString("content"), rs.getDouble("distance")));
        });
    }

    /** Re-indexes every published document of the current tenant. Throws {@link AiUnavailableException}. */
    public RebuildResult rebuild() {
        TenantContext.requireTenantId();
        Map<KnowledgeDocument, List<Chunk>> embedded = new LinkedHashMap<>();
        for (KnowledgeSource source : sources.values()) {
            for (KnowledgeDocument document : source.published()) {
                embedded.put(document, embed(document));
            }
        }
        return tx.execute(s -> {
            locks.lock(LOCK_SCOPE);
            jdbc.update("DELETE FROM ai_chunks WHERE tenant_id = ?", TenantContext.requireTenantId());
            int indexedSources = 0;
            int indexedChunks = 0;
            for (Map.Entry<KnowledgeDocument, List<Chunk>> entry : embedded.entrySet()) {
                KnowledgeDocument document = entry.getKey();
                // only what is still published, unchanged, under the lock
                if (!Optional.of(document).equals(source(document.sourceType()).findPublished(document.sourceId()))) {
                    continue;
                }
                insert(entry.getValue());
                indexedSources++;
                indexedChunks += entry.getValue().size();
            }
            return new RebuildResult(indexedSources, indexedChunks);
        });
    }

    /** Chunks indexed for the current tenant. */
    public long chunkCount() {
        Long count = jdbc.queryForObject("SELECT count(*) FROM ai_chunks WHERE tenant_id = ?", Long.class,
                TenantContext.requireTenantId());
        return count == null ? 0 : count;
    }

    public record RebuildResult(int indexedSources, int indexedChunks) {}

    private record Chunk(KnowledgeDocument document, int index, String content, String embedding) {}

    private KnowledgeSource source(String sourceType) {
        KnowledgeSource source = sources.get(sourceType);
        if (source == null) {
            throw new IllegalArgumentException("No knowledge source for " + sourceType);
        }
        return source;
    }

    /** Chunks the body and embeds {@code title + "\n\n" + chunk} in batches (D5). No transaction is needed. */
    private List<Chunk> embed(KnowledgeDocument document) {
        List<String> contents = Chunker.chunks(document.body());
        List<Chunk> chunks = new ArrayList<>(contents.size());
        for (int from = 0; from < contents.size(); from += EMBED_BATCH) {
            List<String> batch = contents.subList(from, Math.min(from + EMBED_BATCH, contents.size()));
            List<float[]> vectors = ai.embed(batch.stream().map(c -> document.title() + "\n\n" + c).toList());
            if (vectors.size() != batch.size()) {
                throw new AiUnavailableException("ai-service returned " + vectors.size() + " vectors for "
                        + batch.size() + " texts");
            }
            for (int i = 0; i < batch.size(); i++) {
                chunks.add(new Chunk(document, from + i, batch.get(i), vector(vectors.get(i))));
            }
        }
        return chunks;
    }

    private void delete(String sourceType, UUID sourceId) {
        jdbc.update("DELETE FROM ai_chunks WHERE tenant_id = ? AND source_type = ? AND source_id = ?",
                TenantContext.requireTenantId(), sourceType, sourceId);
    }

    private void insert(List<Chunk> chunks) {
        if (chunks.isEmpty()) {
            return;
        }
        UUID tenant = TenantContext.requireTenantId();
        Timestamp now = Timestamp.from(Instant.now());
        jdbc.batchUpdate(INSERT, chunks, chunks.size(), (ps, c) -> {
            ps.setObject(1, Ids.newId());
            ps.setObject(2, tenant);
            ps.setString(3, c.document().sourceType());
            ps.setObject(4, c.document().sourceId());
            ps.setInt(5, c.index());
            ps.setString(6, c.document().title());
            ps.setString(7, c.content());
            ps.setString(8, c.document().requiredPermission());
            ps.setString(9, c.embedding());
            ps.setTimestamp(10, now);
        });
    }

    /** pgvector's text form, {@code [v1,v2,...]}. */
    static String vector(float[] values) {
        StringBuilder sb = new StringBuilder(values.length * 12).append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(values[i]);
        }
        return sb.append(']').toString();
    }
}
