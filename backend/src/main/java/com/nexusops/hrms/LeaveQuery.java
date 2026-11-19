package com.nexusops.hrms;

import java.time.LocalDate;
import java.util.UUID;

/** All filters optional; {@code from}/{@code to} keep requests overlapping that range. */
public record LeaveQuery(LeaveStatus status, UUID employeeId, LocalDate from, LocalDate to) {}
