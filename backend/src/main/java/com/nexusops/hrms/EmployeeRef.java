package com.nexusops.hrms;

import java.util.UUID;

public record EmployeeRef(UUID id, String name, String employeeNumber) {}
