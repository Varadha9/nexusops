package com.nexusops.hrms;

import java.math.BigDecimal;
import java.util.UUID;

public record LeaveAdjustmentCommand(UUID typeId, Integer year, BigDecimal days, String reason) {}
