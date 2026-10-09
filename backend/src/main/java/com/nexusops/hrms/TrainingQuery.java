package com.nexusops.hrms;

import java.util.UUID;

/** All optional; {@code overdue} true keeps open assignments whose due date has passed. */
public record TrainingQuery(UUID employeeId, TrainingStatus status, Boolean overdue) {}
