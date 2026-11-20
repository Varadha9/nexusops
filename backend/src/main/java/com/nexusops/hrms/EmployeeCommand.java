package com.nexusops.hrms;

import java.time.LocalDate;
import java.util.UUID;

/** Raw hire input (D2): {@code personId} or {@code person}, not both. {@code onboardingTemplateId} is used by Task 3. */
public record EmployeeCommand(UUID personId, NewPerson person, String employeeNumber, UUID departmentId,
        UUID jobRoleId, UUID managerId, EmploymentType employmentType, LocalDate startDate, UUID userId,
        UUID onboardingTemplateId) {}
