package com.nexusops.ai;

import static com.nexusops.support.PostgresAssertions.assertDeniedByPostgres;
import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.FakeAiClient;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The database alone isolates every AI table, independent of application code. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AiRlsIT extends IntegrationTestSupport {

    static final List<String> TABLES = List.of("ai_chunks", "ai_settings", "ai_requests");
    static final String VECTOR = KnowledgeIndex.vector(FakeAiClient.vector("beta chunk"));

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID chunk = UUID.randomUUID();
    final UUID request = UUID.randomUUID();

    @Autowired JdbcTemplate contextStarted; // starts the application (and Flyway) before @BeforeAll runs

    @BeforeAll
    void seedTenantB() {
        Timestamp now = Timestamp.from(Instant.now());
        for (UUID t : new UUID[] {tenantA, tenantB}) {
            OwnerJdbc.jdbc().update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                    + "values (?, ?, 'T', 'ACTIVE', 'FREE', ?, ?)", t, "ai-" + t.toString().substring(0, 8), now, now);
        }
        JdbcTemplate b = OwnerJdbc.ownerAs(tenantB);
        b.update("insert into ai_chunks (id, tenant_id, source_type, source_id, chunk_index, title, content, "
                + "required_permission, embedding, created_at) values (?, ?, 'KB_ARTICLE', ?, 0, 'Beta', 'Beta chunk', "
                + "'helpdesk.article.read', ?::vector, ?)", chunk, tenantB, UUID.randomUUID(), VECTOR, now);
        b.update("insert into ai_settings (tenant_id, enabled, monthly_limit, updated_at) values (?, true, 500, ?)",
                tenantB, now);
        b.update("insert into ai_requests (id, tenant_id, feature, mode, model, prompt_version, latency_ms, "
                + "created_at) values (?, ?, 'POLICY_QUESTION', 'LOCAL', 'bge-small-en-v1.5', 'policy-v1', 12, ?)",
                request, tenantB, now);
    }

    private static JdbcTemplate app(String tenantSetting) {
        return OwnerJdbc.tenantScoped("nexusops_app", APP_PASSWORD, tenantSetting);
    }

    @Test
    void anotherTenantsContextAndNoContextSeeNothing() {
        for (String table : TABLES) {
            assertThat(app(tenantA.toString()).queryForObject("select count(*) from " + table, Long.class)).as(table)
                    .isZero();
            assertThat(app("").queryForObject("select count(*) from " + table, Long.class)).as(table).isZero();
            assertThat(app(tenantB.toString()).queryForObject("select count(*) from " + table, Long.class)).as(table)
                    .isPositive();
        }
    }

    /** Valid for tenant B, so a rejection for anyone else is RLS alone. */
    private void insertTenantBRow(JdbcTemplate as, String table) {
        Timestamp now = Timestamp.from(Instant.now());
        switch (table) {
            case "ai_chunks" -> as.update("insert into ai_chunks (id, tenant_id, source_type, source_id, chunk_index, "
                    + "title, content, required_permission, embedding, created_at) values (?, ?, 'HR_POLICY', ?, 0, "
                    + "'T', 'C', 'hr.self.service', ?::vector, ?)", UUID.randomUUID(), tenantB, UUID.randomUUID(),
                    VECTOR, now);
            case "ai_settings" -> {
                // one row per tenant: insert-and-remove as the owner first so the slot is free for the tested insert
                OwnerJdbc.ownerAs(tenantB).update("delete from ai_settings where tenant_id = ?", tenantB);
                as.update("insert into ai_settings (tenant_id, enabled, monthly_limit, updated_at) "
                        + "values (?, false, 10, ?)", tenantB, now);
            }
            case "ai_requests" -> as.update("insert into ai_requests (id, tenant_id, feature, mode, model, "
                    + "prompt_version, latency_ms, created_at) values (?, ?, 'TICKET_TRIAGE', 'LOCAL', "
                    + "'bge-small-en-v1.5', 'triage-v1', 5, ?)", UUID.randomUUID(), tenantB, now);
            default -> throw new IllegalArgumentException(table);
        }
    }

    @Test
    void insertsIntoAnotherTenantAreRejectedByRowLevelSecurity() {
        for (String table : TABLES) {
            assertDeniedByPostgres(() -> insertTenantBRow(app(tenantA.toString()), table), table + " as tenant A");
            assertDeniedByPostgres(() -> insertTenantBRow(app(""), table), table + " without tenant context");
            insertTenantBRow(app(tenantB.toString()), table); // positive control
        }
    }

    @Test
    void updatesAndDeletesOfAnotherTenantsRowsAffectNothing() {
        assertThat(app(tenantB.toString()).update("update ai_chunks set title = title where id = ?", chunk)).isOne();
        assertThat(app(tenantA.toString()).update("update ai_chunks set title = 'Evil' where id = ?", chunk)).isZero();
        assertThat(app(tenantA.toString()).update("delete from ai_chunks where id = ?", chunk)).isZero();
        assertThat(app(tenantA.toString()).update("update ai_settings set monthly_limit = 1 where tenant_id = ?",
                tenantB)).isZero();
        assertThat(app(tenantA.toString()).update("delete from ai_settings where tenant_id = ?", tenantB)).isZero();
        assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select title from ai_chunks where id = ?", String.class,
                chunk)).isEqualTo("Beta");
        assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select count(*) from ai_settings", Long.class)).isOne();
    }

    @Test
    void theUsageLogIsAppendOnlyEvenForItsOwnTenant() {
        assertDeniedByPostgres(() -> app(tenantB.toString()).update("update ai_requests set latency_ms = 1 "
                + "where id = ?", request), "update ai_requests");
        assertDeniedByPostgres(() -> app(tenantB.toString()).update("delete from ai_requests where id = ?", request),
                "delete ai_requests");
        assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select latency_ms from ai_requests where id = ?",
                Integer.class, request)).isEqualTo(12);
    }
}
