package com.nexusops.hrms;

import java.util.UUID;

/** {@code status} null → everyone who hasn't left (any status when {@code personId} is given). */
public record EmployeeQuery(String q, EmploymentStatus status, UUID departmentId, UUID jobRoleId, UUID managerId,
        UUID personId) {}
