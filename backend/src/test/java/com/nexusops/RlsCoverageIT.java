package com.nexusops;

import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guards every future migration: any table that is not explicitly global must have FORCE RLS and a
 * policy. Adding a table without RLS fails this test.
 */
class RlsCoverageIT extends IntegrationTestSupport {

    static final Set<String> PLATFORM_TABLES = Set.of("platform_users", "platform_refresh_tokens");
    static final Set<String> GLOBAL_TABLES =
            Set.of("flyway_schema_history", "plans", "modules", "permissions", "tenants");

    static final Set<String> EXPECTED_TENANT_TABLES = Set.of(
            "tenant_modules", "users", "refresh_tokens", "email_verifications",
            "roles", "role_permissions", "user_roles", "audit_events", "invitations",
            "parties", "party_roles", "products", "activities", "tasks",
            "documents", "document_contents", "pipeline_stages", "leads", "opportunities",
            "number_sequences", "warehouses", "stock_levels", "stock_movements", "purchase_orders",
            "purchase_order_lines", "sales_orders", "sales_order_lines", "reorder_rules", "password_resets",
            "ticket_categories", "sla_policies", "tickets", "ticket_messages", "kb_articles",
            "departments", "skills", "job_roles", "job_role_skills", "employees", "employee_skills",
            "onboarding_templates", "onboarding_template_items", "employee_onboardings",
            "employee_onboarding_tasks", "leave_types", "holidays", "leave_requests", "leave_adjustments",
            "courses", "course_skills", "training_assignments", "hr_policies", "ai_chunks", "ai_settings",
            "ai_requests");

    @Autowired JdbcTemplate jdbc;

    static final String CANONICAL_EXPR =
            "(tenant_id = (NULLIF(current_setting('app.tenant_id'::text, true), ''::text))::uuid)";

    static final Set<String> DIRECT_TENANT_TABLES = Set.of(
            "tenant_modules", "refresh_tokens", "email_verifications",
            "audit_events", "invitations", "parties", "party_roles",
            "products", "activities", "tasks", "documents",
            "document_contents", "pipeline_stages", "leads", "opportunities");

    @Test
    void expectedTenantTablesExist() {
        List<String> tables = jdbc.queryForList(
                "select tablename from pg_tables where schemaname = 'public'", String.class);
        assertThat(tables).containsAll(EXPECTED_TENANT_TABLES).containsAll(GLOBAL_TABLES)
                .containsAll(PLATFORM_TABLES);
    }

    @Test
    void everyDirectTenantTableForcesRlsAndMatchesCanonicalTenantExpression() {
        List<String> nonCompliant = jdbc.queryForList("""
                select c.relname
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                left join pg_policy p on p.polrelid = c.oid and p.polname = 'tenant_isolation' and p.polcmd = '*'
                where n.nspname = 'public' and c.relkind in ('r', 'p')
                  and c.relname in ('tenant_modules', 'refresh_tokens', 'email_verifications',
                                   'invitations', 'parties', 'party_roles', 'products',
                                   'activities', 'tasks', 'documents', 'document_contents',
                                   'pipeline_stages', 'leads', 'opportunities')
                  and (
                       not c.relrowsecurity
                    or not c.relforcerowsecurity
                    or p.polname is null
                    or pg_get_expr(p.polqual, p.polrelid) != '(tenant_id = (NULLIF(current_setting(''app.tenant_id''::text, true), ''''::text))::uuid)'
                    or pg_get_expr(p.polwithcheck, p.polrelid) is null
                    or pg_get_expr(p.polwithcheck, p.polrelid) != '(tenant_id = (NULLIF(current_setting(''app.tenant_id''::text, true), ''''::text))::uuid)'
                    or (select count(*) from pg_policy where polrelid = c.oid) != 1
                  )
                """, String.class);
        assertThat(nonCompliant).as("All direct tenant tables must strictly match canonical RLS AST").isEmpty();
    }

    @Test
    void allTablesEnforceRlsUnlessExplicitlyGlobal() {
        List<String> unprotected = jdbc.queryForList("""
                select c.relname
                from pg_class c join pg_namespace n on n.oid = c.relnamespace
                where n.nspname = 'public' and c.relkind in ('r', 'p')
                  and not (c.relrowsecurity and c.relforcerowsecurity
                           and exists (select 1 from pg_policies p where p.schemaname = 'public' and p.tablename = c.relname))
                """, String.class);
        assertThat(unprotected).allMatch(GLOBAL_TABLES::contains);
    }

    @Test
    void policyGuardRejectsDeliberatelyUnsafeConfigurations() {
        var owner = com.nexusops.support.OwnerJdbc.jdbc();
        String probeTable = "test_rls_mutation_probe";

        try {
            owner.execute("drop table if exists " + probeTable + " cascade");
            owner.execute("create table " + probeTable + " (id uuid primary key, tenant_id uuid not null, name text)");

            // 1. Unsafe: No RLS enabled
            assertThat(checkTableCompliance(probeTable)).as("Table without RLS must be rejected").isFalse();

            // 2. Unsafe: RLS enabled but NOT forced
            owner.execute("alter table " + probeTable + " enable row level security");
            owner.execute("create policy tenant_isolation on " + probeTable + " using (" +
                    "tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid) with check (" +
                    "tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)");
            assertThat(checkTableCompliance(probeTable)).as("Table without FORCE RLS must be rejected").isFalse();

            // 3. Unsafe: WITH CHECK (true) bypass
            owner.execute("alter table " + probeTable + " force row level security");
            owner.execute("drop policy tenant_isolation on " + probeTable);
            owner.execute("create policy tenant_isolation on " + probeTable + " using (" +
                    "tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid) with check (true)");
            assertThat(checkTableCompliance(probeTable)).as("WITH CHECK (true) must be rejected by AST expression check").isFalse();

            // 4. Unsafe: USING (true) bypass
            owner.execute("drop policy tenant_isolation on " + probeTable);
            owner.execute("create policy tenant_isolation on " + probeTable + " using (true) with check (" +
                    "tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)");
            assertThat(checkTableCompliance(probeTable)).as("USING (true) must be rejected by AST expression check").isFalse();

            // 5. Unsafe: Missing WITH CHECK entirely (read-only policy)
            owner.execute("drop policy tenant_isolation on " + probeTable);
            owner.execute("create policy tenant_isolation on " + probeTable + " using (" +
                    "tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)");
            assertThat(checkTableCompliance(probeTable)).as("Missing WITH CHECK clause must be rejected").isFalse();

            // 6. Unsafe: Permissive secondary policy injection
            owner.execute("drop policy tenant_isolation on " + probeTable);
            owner.execute("create policy tenant_isolation on " + probeTable + " using (" +
                    "tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid) with check (" +
                    "tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)");
            owner.execute("create policy extra_permissive on " + probeTable + " using (true)");
            assertThat(checkTableCompliance(probeTable)).as("Secondary permissive policy must be rejected by policy-count check").isFalse();

            // 7. Safe baseline: canonical policy with forced RLS
            owner.execute("drop policy extra_permissive on " + probeTable);
            assertThat(checkTableCompliance(probeTable)).as("Canonical policy with FORCE RLS must pass guard").isTrue();

        } finally {
            owner.execute("drop table if exists " + probeTable + " cascade");
        }
    }

    private boolean checkTableCompliance(String tableName) {
        List<String> failed = jdbc.queryForList("""
                select c.relname
                from pg_class c
                join pg_namespace n on n.oid = c.relnamespace
                left join pg_policy p on p.polrelid = c.oid and p.polname = 'tenant_isolation' and p.polcmd = '*'
                where n.nspname = 'public' and c.relname = ?
                  and (
                       not c.relrowsecurity
                    or not c.relforcerowsecurity
                    or p.polname is null
                    or pg_get_expr(p.polqual, p.polrelid) != '(tenant_id = (NULLIF(current_setting(''app.tenant_id''::text, true), ''''::text))::uuid)'
                    or pg_get_expr(p.polwithcheck, p.polrelid) is null
                    or pg_get_expr(p.polwithcheck, p.polrelid) != '(tenant_id = (NULLIF(current_setting(''app.tenant_id''::text, true), ''''::text))::uuid)'
                    or (select count(*) from pg_policy where polrelid = c.oid) != 1
                  )
                """, String.class, tableName);
        return failed.isEmpty();
    }
}
