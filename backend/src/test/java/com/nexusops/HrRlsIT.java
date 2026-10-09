package com.nexusops;

import static com.nexusops.support.PostgresAssertions.assertDeniedByPostgres;
import static org.assertj.core.api.Assertions.assertThat;

import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** The database alone isolates every Phase 8 table, independent of application code. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HrRlsIT extends IntegrationTestSupport {

    static final List<String> TABLES = List.of("departments", "skills", "job_roles", "job_role_skills", "employees",
            "employee_skills", "onboarding_templates", "onboarding_template_items", "employee_onboardings",
            "employee_onboarding_tasks", "leave_types", "holidays", "leave_requests", "leave_adjustments", "courses",
            "course_skills", "training_assignments", "hr_policies");

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID person = UUID.randomUUID();
    final UUID department = UUID.randomUUID();
    final UUID skill = UUID.randomUUID();
    final UUID jobRole = UUID.randomUUID();
    final UUID employee = UUID.randomUUID();
    final UUID template = UUID.randomUUID();
    final UUID onboarding = UUID.randomUUID();
    final UUID task = UUID.randomUUID();
    final UUID leaveType = UUID.randomUUID();
    final UUID leaveRequest = UUID.randomUUID();
    final UUID adjustment = UUID.randomUUID();
    final UUID course = UUID.randomUUID();
    final UUID assignment = UUID.randomUUID();
    final UUID policy = UUID.randomUUID();
    final AtomicInteger counter = new AtomicInteger();

    @Autowired JdbcTemplate contextStarted; // starts the application (and Flyway) before @BeforeAll runs

    @BeforeAll
    void seedTenantB() {
        Timestamp now = Timestamp.from(Instant.now());
        for (UUID t : new UUID[] {tenantA, tenantB}) {
            OwnerJdbc.jdbc().update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                    + "values (?, ?, 'T', 'ACTIVE', 'FREE', ?, ?)", t, "hr-" + t.toString().substring(0, 8), now, now);
        }
        JdbcTemplate b = OwnerJdbc.ownerAs(tenantB);
        b.update("insert into parties (id, tenant_id, kind, name, name_key, first_name, last_name, created_at, updated_at) "
                + "values (?, ?, 'PERSON', 'Beta Person', 'beta person', 'Beta', 'Person', ?, ?)", person, tenantB, now, now);
        b.update("insert into departments (id, tenant_id, name, name_key, created_at, updated_at) "
                + "values (?, ?, 'Beta dept', 'beta dept', ?, ?)", department, tenantB, now, now);
        b.update("insert into skills (id, tenant_id, name, name_key, created_at, updated_at) "
                + "values (?, ?, 'Beta skill', 'beta skill', ?, ?)", skill, tenantB, now, now);
        b.update("insert into job_roles (id, tenant_id, title, title_key, department_id, created_at, updated_at) "
                + "values (?, ?, 'Beta role', 'beta role', ?, ?, ?)", jobRole, tenantB, department, now, now);
        b.update("insert into job_role_skills (id, tenant_id, job_role_id, skill_id, minimum_level) values (?, ?, ?, ?, 3)",
                UUID.randomUUID(), tenantB, jobRole, skill);
        b.update("insert into employees (id, tenant_id, person_id, employee_number, number_key, department_id, "
                + "job_role_id, employment_type, start_date, created_at, updated_at) values (?, ?, ?, 'B-1', 'b-1', ?, ?, "
                + "'FULL_TIME', date '2026-01-05', ?, ?)", employee, tenantB, person, department, jobRole, now, now);
        b.update("insert into employee_skills (id, tenant_id, employee_id, skill_id, level) values (?, ?, ?, ?, 2)",
                UUID.randomUUID(), tenantB, employee, skill);
        b.update("insert into onboarding_templates (id, tenant_id, name, name_key, created_at, updated_at) "
                + "values (?, ?, 'Beta onboarding', 'beta onboarding', ?, ?)", template, tenantB, now, now);
        b.update("insert into onboarding_template_items (id, tenant_id, template_id, position, title, owner, "
                + "due_offset_days) values (?, ?, ?, 0, 'Beta item', 'HR', 0)", UUID.randomUUID(), tenantB, template);
        b.update("insert into employee_onboardings (id, tenant_id, employee_id, template_id, template_name, started_at) "
                + "values (?, ?, ?, ?, 'Beta onboarding', ?)", onboarding, tenantB, employee, template, now);
        b.update("insert into tasks (id, tenant_id, title, status, priority, created_at, updated_at) "
                + "values (?, ?, 'Beta task', 'OPEN', 'NORMAL', ?, ?)", task, tenantB, now, now);
        b.update("insert into employee_onboarding_tasks (id, tenant_id, onboarding_id, task_id) values (?, ?, ?, ?)",
                UUID.randomUUID(), tenantB, onboarding, task);
        b.update("insert into leave_types (id, tenant_id, name, name_key, paid, annual_allowance, position, created_at, "
                + "updated_at) values (?, ?, 'Beta leave', 'beta leave', true, 20, 0, ?, ?)", leaveType, tenantB, now, now);
        b.update("insert into holidays (id, tenant_id, day, name, created_at) values (?, ?, date '2027-01-26', 'Beta day', ?)",
                UUID.randomUUID(), tenantB, now);
        b.update("insert into leave_requests (id, tenant_id, employee_id, type_id, start_date, end_date, days, status, "
                + "requested_at, updated_at) values (?, ?, ?, ?, date '2027-03-01', date '2027-03-01', 1, 'PENDING', ?, ?)",
                leaveRequest, tenantB, employee, leaveType, now, now);
        b.update("insert into leave_adjustments (id, tenant_id, employee_id, type_id, year, days, reason, created_at) "
                + "values (?, ?, ?, ?, 2027, 1.5, 'Beta', ?)", adjustment, tenantB, employee, leaveType, now);
        b.update("insert into courses (id, tenant_id, title, created_at, updated_at) values (?, ?, 'Beta course', ?, ?)",
                course, tenantB, now, now);
        b.update("insert into course_skills (id, tenant_id, course_id, skill_id, level) values (?, ?, ?, ?, 3)",
                UUID.randomUUID(), tenantB, course, skill);
        b.update("insert into training_assignments (id, tenant_id, course_id, employee_id, status, assigned_at, "
                + "updated_at) values (?, ?, ?, ?, 'ASSIGNED', ?, ?)", assignment, tenantB, course, employee, now, now);
        b.update("insert into hr_policies (id, tenant_id, title, body, status, created_at, updated_at) "
                + "values (?, ?, 'Beta policy', 'Body', 'DRAFT', ?, ?)", policy, tenantB, now, now);
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

    /** Valid for tenant B (fresh keys and numbers each call), so a rejection for anyone else is RLS alone. */
    private void insertTenantBRow(JdbcTemplate as, String table) {
        Timestamp now = Timestamp.from(Instant.now());
        int n = counter.incrementAndGet();
        UUID id = UUID.randomUUID();
        switch (table) {
            case "departments" -> as.update("insert into departments (id, tenant_id, name, name_key, created_at, "
                    + "updated_at) values (?, ?, ?, ?, ?, ?)", id, tenantB, "Dept " + n, "dept " + n, now, now);
            case "skills" -> as.update("insert into skills (id, tenant_id, name, name_key, created_at, updated_at) "
                    + "values (?, ?, ?, ?, ?, ?)", id, tenantB, "Skill " + n, "skill " + n, now, now);
            case "employees" -> {
                UUID newPerson = UUID.randomUUID();
                as.update("insert into parties (id, tenant_id, kind, name, name_key, first_name, last_name, created_at, "
                        + "updated_at) values (?, ?, 'PERSON', ?, ?, 'Beta', ?, ?, ?)", newPerson, tenantB,
                        "Beta Person " + n, "beta person " + n, "Person " + n, now, now);
                as.update("insert into employees (id, tenant_id, person_id, employee_number, number_key, "
                        + "employment_type, start_date, created_at, updated_at) values (?, ?, ?, ?, ?, 'FULL_TIME', "
                        + "date '2026-01-05', ?, ?)", id, tenantB, newPerson, "E-" + n, "e-" + n, now, now);
            }
            case "leave_types" -> as.update("insert into leave_types (id, tenant_id, name, name_key, paid, "
                    + "annual_allowance, position, created_at, updated_at) values (?, ?, ?, ?, true, 10, 1, ?, ?)", id,
                    tenantB, "Leave " + n, "leave " + n, now, now);
            case "holidays" -> as.update("insert into holidays (id, tenant_id, day, name, created_at) "
                    + "values (?, ?, date '2030-01-01' + ?, ?, ?)", id, tenantB, n, "Holiday " + n, now);
            case "leave_requests" -> as.update("insert into leave_requests (id, tenant_id, employee_id, type_id, "
                    + "start_date, end_date, days, status, requested_at, updated_at) values (?, ?, ?, ?, "
                    + "date '2028-01-01' + ?, date '2028-01-01' + ?, 1, 'PENDING', ?, ?)", id, tenantB, employee,
                    leaveType, n, n, now, now);
            case "leave_adjustments" -> as.update("insert into leave_adjustments (id, tenant_id, employee_id, type_id, "
                    + "year, days, reason, created_at) values (?, ?, ?, ?, 2027, 1, 'x', ?)", id, tenantB, employee,
                    leaveType, now);
            case "courses" -> as.update("insert into courses (id, tenant_id, title, created_at, updated_at) "
                    + "values (?, ?, ?, ?, ?)", id, tenantB, "Course " + n, now, now);
            case "training_assignments" -> {
                // one open assignment per course and employee: every call gets its own course
                UUID newCourse = UUID.randomUUID();
                as.update("insert into courses (id, tenant_id, title, created_at, updated_at) values (?, ?, ?, ?, ?)",
                        newCourse, tenantB, "Assigned course " + n, now, now);
                as.update("insert into training_assignments (id, tenant_id, course_id, employee_id, status, assigned_at, "
                        + "updated_at) values (?, ?, ?, ?, 'ASSIGNED', ?, ?)", id, tenantB, newCourse, employee, now, now);
            }
            case "hr_policies" -> as.update("insert into hr_policies (id, tenant_id, title, body, status, created_at, "
                    + "updated_at) values (?, ?, ?, 'y', 'DRAFT', ?, ?)", id, tenantB, "Policy " + n, now, now);
            default -> throw new IllegalArgumentException(table);
        }
    }

    @Test
    void insertsIntoAnotherTenantAreRejectedByRowLevelSecurity() {
        for (String table : List.of("departments", "skills", "employees", "leave_types", "holidays", "leave_requests",
                "leave_adjustments", "courses", "training_assignments", "hr_policies")) {
            assertDeniedByPostgres(() -> insertTenantBRow(app(tenantA.toString()), table), table + " as tenant A");
            assertDeniedByPostgres(() -> insertTenantBRow(app(""), table), table + " without tenant context");
            insertTenantBRow(app(tenantB.toString()), table); // positive control
        }
    }

    @Test
    void updatesAndDeletesOfAnotherTenantsRowsAffectNothing() {
        record Case(String table, String update, String sameValue, String delete, UUID id) {}
        List<Case> cases = List.of(
                new Case("departments", "update departments set name = 'Evil' where id = ?",
                        "update departments set name = name where id = ?", "delete from departments where id = ?",
                        department),
                new Case("employees", "update employees set employee_number = 'EVIL' where id = ?",
                        "update employees set employee_number = employee_number where id = ?",
                        "delete from employees where id = ?", employee),
                new Case("leave_requests", "update leave_requests set status = 'APPROVED' where id = ?",
                        "update leave_requests set status = status where id = ?",
                        "delete from leave_requests where id = ?", leaveRequest),
                new Case("courses", "update courses set title = 'Evil' where id = ?",
                        "update courses set title = title where id = ?", "delete from courses where id = ?", course),
                new Case("hr_policies", "update hr_policies set title = 'Evil' where id = ?",
                        "update hr_policies set title = title where id = ?", "delete from hr_policies where id = ?",
                        policy));
        for (Case c : cases) {
            // positive control: the owning tenant reaches the very same row with the very same statement shape
            assertThat(app(tenantB.toString()).update(c.sameValue(), c.id())).as(c.table() + " as tenant B").isEqualTo(1);
            assertThat(app(tenantA.toString()).update(c.update(), c.id())).as(c.table() + " update").isZero();
            assertThat(app(tenantA.toString()).update(c.delete(), c.id())).as(c.table() + " delete").isZero();
            assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select count(*) from " + c.table() + " where id = ?",
                    Long.class, c.id())).as(c.table() + " still exists").isEqualTo(1);
        }
        assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select name from departments where id = ?", String.class,
                department)).isEqualTo("Beta dept");
        assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select status from leave_requests where id = ?",
                String.class, leaveRequest)).isEqualTo("PENDING");
    }

    @Test
    void leaveAdjustmentsAreAppendOnlyEvenForTheirOwnTenant() {
        assertDeniedByPostgres(() -> app(tenantB.toString()).update("update leave_adjustments set reason = 'x' "
                + "where id = ?", adjustment), "update leave_adjustments");
        assertDeniedByPostgres(() -> app(tenantB.toString()).update("delete from leave_adjustments where id = ?",
                adjustment), "delete leave_adjustments");
        assertThat(OwnerJdbc.ownerAs(tenantB).queryForObject("select reason from leave_adjustments where id = ?",
                String.class, adjustment)).isEqualTo("Beta");
    }
}
