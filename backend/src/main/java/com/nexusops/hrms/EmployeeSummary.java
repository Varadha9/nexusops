package com.nexusops.hrms;

import java.time.LocalDate;
import java.util.UUID;

public record EmployeeSummary(UUID id, String name, String employeeNumber, DepartmentRef department, JobRoleRef jobRole,
        EmployeeRef manager, EmploymentStatus status, LocalDate startDate, LocalDate endDate) {}
