package com.nexusops.hrms;

import java.time.LocalDate;
import java.util.UUID;

public record LeaveRequestCommand(UUID typeId, LocalDate startDate, LocalDate endDate, String reason) {}
