package com.nexusops.hrms;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** D13: Monday to Friday in the range, inclusive, minus the workspace's public holidays. */
final class WorkingDays {

    private WorkingDays() {}

    static int count(LocalDate start, LocalDate end, Set<LocalDate> holidays) {
        int days = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(d)) {
                days++;
            }
        }
        return days;
    }
}
