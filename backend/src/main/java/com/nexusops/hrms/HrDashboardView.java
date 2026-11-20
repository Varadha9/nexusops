package com.nexusops.hrms;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** D19. */
@Schema(name = "HrDashboardView")
public record HrDashboardView(long activeHeadcount, List<DepartmentCount> byDepartment, long upcomingStarts,
        long joinersLast90Days, long leaversLast90Days, List<AwayView> awayToday, List<AwayView> awayNext7Days,
        long pendingLeaveRequests, long overdueTrainings, long onboardingInProgress, List<SkillGapCount> topSkillGaps) {

    @Schema(name = "HrDepartmentCount")
    public record DepartmentCount(DepartmentRef department, long employees) {}

    @Schema(name = "HrSkillGapCount")
    public record SkillGapCount(SkillRef skill, long employees) {}
}
