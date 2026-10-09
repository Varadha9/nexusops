package com.nexusops.hrms;

import com.nexusops.hrms.domain.Department;
import com.nexusops.hrms.domain.Skill;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** D19: the workforce at a glance. */
@Service
public class HrDashboardService {

    private final HrQueries queries;
    private final HrClock clock;
    private final DepartmentService departments;
    private final SkillService skills;
    private final LeaveService leave;
    private final TrainingService training;

    HrDashboardService(HrQueries queries, HrClock clock, DepartmentService departments, SkillService skills,
            LeaveService leave, TrainingService training) {
        this.queries = queries;
        this.clock = clock;
        this.departments = departments;
        this.skills = skills;
        this.leave = leave;
        this.training = training;
    }

    @Transactional(readOnly = true)
    public HrDashboardView dashboard() {
        LocalDate today = clock.today();
        HrQueries.Counts c = queries.counts(today);
        List<Map<String, Object>> depts = queries.activeByDepartment(today);
        Map<UUID, Department> deptNames = departments.byIds(depts.stream().map(r -> (UUID) r.get("department_id"))
                .filter(Objects::nonNull).toList());
        List<Map<String, Object>> gaps = queries.topSkillGaps(today, 5);
        Map<UUID, Skill> skillNames = skills.byIds(gaps.stream().map(r -> (UUID) r.get("skill_id")).toList());
        return new HrDashboardView(c.active(),
                depts.stream().map(r -> new HrDashboardView.DepartmentCount(r.get("department_id") == null ? null
                        : DepartmentService.ref(deptNames.get((UUID) r.get("department_id"))),
                        ((Number) r.get("n")).longValue())).toList(),
                c.upcoming(), c.joiners(), c.leavers(),
                leave.away(today, today), leave.away(today.plusDays(1), today.plusDays(7)),
                leave.pendingCount(), training.overdueCount(), queries.onboardingInProgress(today),
                gaps.stream().map(r -> new HrDashboardView.SkillGapCount(
                        SkillService.ref(skillNames.get((UUID) r.get("skill_id"))), ((Number) r.get("n")).longValue()))
                        .toList());
    }
}
