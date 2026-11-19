package com.nexusops.hrms;

import com.nexusops.shared.web.ApiProblem;
import java.time.LocalDate;

/**
 * Bounds on user-supplied dates and years (1900–2999), so an out-of-range value is a 400 field error instead
 * of reaching {@code LocalDate.of} or PostgreSQL's date range.
 */
final class HrDates {

    static final int MIN_YEAR = 1900;
    static final int MAX_YEAR = 2999;
    static final String DATE = "Enter a date from " + MIN_YEAR + " to " + MAX_YEAR + ".";
    static final String YEAR = "Enter a year from " + MIN_YEAR + " to " + MAX_YEAR + ".";

    private HrDates() {}

    /** {@code date} unchanged when null or in range; otherwise a 400 on {@code field}. */
    static LocalDate check(LocalDate date, String field) {
        if (date != null && (date.getYear() < MIN_YEAR || date.getYear() > MAX_YEAR)) {
            throw ApiProblem.badRequestField(field, DATE);
        }
        return date;
    }

    static int year(int year, String field) {
        if (year < MIN_YEAR || year > MAX_YEAR) {
            throw ApiProblem.badRequestField(field, YEAR);
        }
        return year;
    }
}
