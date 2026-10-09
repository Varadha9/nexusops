package com.nexusops.hrms;

import java.time.Instant;
import java.util.UUID;

public record LeaveTypeView(UUID id, String name, boolean paid, Integer annualAllowance, Instant archivedAt,
        long version) {}
