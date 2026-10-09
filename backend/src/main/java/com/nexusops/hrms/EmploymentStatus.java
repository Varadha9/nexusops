package com.nexusops.hrms;

import java.time.LocalDate;

/** Derived from the employment dates in the workspace's time zone (D2). The end date is the last working day. */
public enum EmploymentStatus {
    UPCOMING, ACTIVE, LEFT;

    public static EmploymentStatus of(LocalDate start, LocalDate end, LocalDate today) {
        if (end != null && end.isBefore(today)) {
            return LEFT;
        }
        return start.isAfter(today) ? UPCOMING : ACTIVE;
    }
}
