package com.nexusops.hrms;

import java.util.List;

public record SkillGapView(EmployeeRef employee, JobRoleRef jobRole, List<Gap> gaps) {

    public record Gap(SkillRef skill, int required, Integer held, List<CourseRef> recommendedCourses) {}
}
