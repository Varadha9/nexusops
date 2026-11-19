package com.nexusops.hrms;

import java.time.LocalDate;
import java.util.UUID;

/** Published in the ending transaction; Leave (Task 4) and Training (Task 5) react to it. */
public record EmploymentEndedEvent(UUID employeeId, LocalDate endDate) {}
