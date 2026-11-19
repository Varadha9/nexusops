package com.nexusops.hrms;

import com.nexusops.collaboration.MemberRef;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record EmployeeView(UUID id, PersonRef person, String employeeNumber, DepartmentRef department, JobRoleRef jobRole,
        EmployeeRef manager, EmploymentType employmentType, LocalDate startDate, LocalDate endDate, String endReason,
        EmploymentStatus status, MemberRef user, List<EmployeeSkillView> skills, OnboardingProgress onboarding,
        Instant createdAt, Instant updatedAt, long version) {}
