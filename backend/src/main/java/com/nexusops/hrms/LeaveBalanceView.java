package com.nexusops.hrms;

import java.math.BigDecimal;

public record LeaveBalanceView(LeaveTypeRef type, boolean paid, BigDecimal entitlement, BigDecimal taken,
        BigDecimal pending, BigDecimal available) {}
