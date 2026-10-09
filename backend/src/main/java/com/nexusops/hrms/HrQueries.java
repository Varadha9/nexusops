package com.nexusops.hrms;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import java.time.LocalDate;
import java.util.List;
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
}
