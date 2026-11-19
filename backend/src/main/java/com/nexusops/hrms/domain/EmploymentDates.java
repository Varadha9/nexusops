package com.nexusops.hrms.domain;

import java.time.LocalDate;

/** An employee's start and end dates as last committed (a fresh read, not the persistence context's copy). */
public record EmploymentDates(LocalDate startDate, LocalDate endDate) {}
