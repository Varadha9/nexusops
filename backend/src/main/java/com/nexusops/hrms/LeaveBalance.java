package com.nexusops.hrms;

import java.math.BigDecimal;

/** D14 for one employee, type and year. Unlimited types (no allowance) have no entitlement and no available days. */
record LeaveBalance(BigDecimal entitlement, BigDecimal taken, BigDecimal pending, BigDecimal available) {

    static LeaveBalance of(Integer allowance, BigDecimal adjustments, int taken, int pending) {
        BigDecimal t = BigDecimal.valueOf(taken);
        BigDecimal p = BigDecimal.valueOf(pending);
        if (allowance == null) {
            return new LeaveBalance(null, t, p, null);
        }
        BigDecimal entitlement = BigDecimal.valueOf(allowance).add(adjustments);
        return new LeaveBalance(entitlement, t, p, entitlement.subtract(t).subtract(p));
    }

    /** "2.5", "3", "0": for messages. */
    static String days(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.signum() == 0 ? "0" : stripped.toPlainString();
    }
}
