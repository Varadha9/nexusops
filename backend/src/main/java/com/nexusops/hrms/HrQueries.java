package com.nexusops.hrms;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** SQL read models for HRMS. The status fragments mirror EmploymentStatus — keep the two in step. */
@Component
class HrQueries {

    /** e = employees, :today bound. */
    static final String LEFT = "(e.end_date is not null and e.end_date < :today)";
    static final String UPCOMING = "(not " + LEFT + " and e.start_date > :today)";
    static final String ACTIVE = "(not " + LEFT + " and e.start_date <= :today)";

    record Page(List<UUID> ids, long total) {}

    private final NamedParameterJdbcTemplate jdbc;

    HrQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Page employeePage(EmployeeQuery q, LocalDate today, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", TenantContext.requireTenantId())
                .addValue("today", today);
        StringBuilder where = new StringBuilder("e.tenant_id = :tenant and p.tenant_id = :tenant");
        if (q.status() != null || q.personId() == null) {
            where.append(" and ").append(q.status() == null ? "not " + LEFT : switch (q.status()) {
                case LEFT -> LEFT;
                case UPCOMING -> UPCOMING;
                case ACTIVE -> ACTIVE;
            });
        }
        if (q.personId() != null) {
            where.append(" and e.person_id = :person");
            params.addValue("person", q.personId());
        }
        if (q.departmentId() != null) {
            where.append(" and e.department_id = :department");
            params.addValue("department", q.departmentId());
        }
        if (q.jobRoleId() != null) {
            where.append(" and e.job_role_id = :jobRole");
            params.addValue("jobRole", q.jobRoleId());
        }
        if (q.managerId() != null) {
            where.append(" and e.manager_id = :manager");
            params.addValue("manager", q.managerId());
        }
        String text = Text.optional(q.q(), 100, "q");
        if (text != null) {
            where.append(" and (lower(p.name) like :pattern escape '\\' or e.number_key like :pattern escape '\\')");
            params.addValue("pattern", Text.containsPattern(text));
        }
        String from = " from employees e join parties p on p.id = e.person_id where " + where;
        Long total = jdbc.queryForObject("select count(*)" + from, params, Long.class);
        params.addValue("limit", limit).addValue("offset", offset);
        List<UUID> ids = jdbc.queryForList("select e.id" + from + " order by lower(p.name), e.id limit :limit offset :offset",
                params, UUID.class);
        return new Page(ids, total == null ? 0 : total);
    }

    /** Employee ids whose person's name or number matches the lower-case LIKE pattern (search, D18). */
    List<UUID> searchEmployees(String pattern, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", TenantContext.requireTenantId())
                .addValue("pattern", pattern).addValue("limit", limit);
        return jdbc.queryForList("select e.id from employees e join parties p on p.id = e.person_id "
                + "where e.tenant_id = :tenant and p.tenant_id = :tenant "
                + "and (lower(p.name) like :pattern escape '\\' or e.number_key like :pattern escape '\\') "
                + "order by lower(p.name), e.id limit :limit", params, UUID.class);
    }

    Page policyPage(String rawQ, List<PolicyStatus> statuses, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", TenantContext.requireTenantId())
                .addValue("statuses", statuses.stream().map(Enum::name).toList());
        StringBuilder where = new StringBuilder("p.tenant_id = :tenant and p.status in (:statuses)");
        String q = Text.optional(rawQ, 200, "q");
        String order = "p.updated_at desc, p.id desc";
        if (q != null) {
            where.append(" and p.search @@ websearch_to_tsquery('simple', :q)");
            params.addValue("q", q);
            order = "ts_rank(p.search, websearch_to_tsquery('simple', :q)) desc, " + order;
        }
        Long total = jdbc.queryForObject("select count(*) from hr_policies p where " + where, params, Long.class);
        params.addValue("limit", limit).addValue("offset", offset);
        List<UUID> ids = jdbc.queryForList("select p.id from hr_policies p where " + where + " order by " + order
                + " limit :limit offset :offset", params, UUID.class);
        return new Page(ids, total == null ? 0 : total);
    }

    record Counts(long active, long upcoming, long joiners, long leavers) {}

    Counts counts(LocalDate today) {
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", TenantContext.requireTenantId())
                .addValue("today", today).addValue("in30", today.plusDays(30)).addValue("ago90", today.minusDays(90));
        Map<String, Object> row = jdbc.queryForMap("select "
                + "count(*) filter (where " + ACTIVE + ") as active, "
                + "count(*) filter (where " + UPCOMING + " and e.start_date <= :in30) as upcoming, "
                + "count(*) filter (where e.start_date between :ago90 and :today) as joiners, "
                + "count(*) filter (where e.end_date >= :ago90 and e.end_date < :today) as leavers "
                + "from employees e where e.tenant_id = :tenant", p);
        return new Counts(((Number) row.get("active")).longValue(), ((Number) row.get("upcoming")).longValue(),
                ((Number) row.get("joiners")).longValue(), ((Number) row.get("leavers")).longValue());
    }

    /** Active employees per department; the null department last. */
    List<Map<String, Object>> activeByDepartment(LocalDate today) {
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", TenantContext.requireTenantId())
                .addValue("today", today);
        return jdbc.queryForList("select e.department_id as department_id, count(*) as n from employees e "
                + "where e.tenant_id = :tenant and " + ACTIVE + " group by e.department_id "
                + "order by e.department_id is null, count(*) desc", p);
    }

    /** Skills most often below their job role's minimum among active employees (D8, D19). */
    List<Map<String, Object>> topSkillGaps(LocalDate today, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", TenantContext.requireTenantId())
                .addValue("today", today).addValue("limit", limit);
        return jdbc.queryForList("""
                select r.skill_id as skill_id, count(*) as n
                from employees e
                join job_role_skills r on r.tenant_id = e.tenant_id and r.job_role_id = e.job_role_id
                left join employee_skills s on s.tenant_id = e.tenant_id and s.employee_id = e.id and s.skill_id = r.skill_id
                where e.tenant_id = :tenant and %s and (s.level is null or s.level < r.minimum_level)
                group by r.skill_id
                order by count(*) desc, r.skill_id
                limit :limit
                """.formatted(ACTIVE), p);
    }

    /** Onboardings with at least one task not done yet. */
    long onboardingInProgress() {
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", TenantContext.requireTenantId());
        Long n = jdbc.queryForObject("""
                select count(distinct o.id) from employee_onboardings o
                join employee_onboarding_tasks l on l.tenant_id = o.tenant_id and l.onboarding_id = o.id
                join tasks t on t.id = l.task_id and t.tenant_id = :tenant
                where o.tenant_id = :tenant and t.status not in ('DONE', 'CANCELLED')
                """, p, Long.class);
        return n == null ? 0 : n;
    }
}
