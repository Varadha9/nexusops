package com.nexusops.hrms;

import java.time.LocalDate;
import java.util.UUID;

public record EmployeeUpdate(String employeeNumber, UUID departmentId, UUID jobRoleId, UUID managerId,
        EmploymentType employmentType, LocalDate startDate, UUID userId) {}
