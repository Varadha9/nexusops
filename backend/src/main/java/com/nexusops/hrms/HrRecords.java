package com.nexusops.hrms;

import com.nexusops.shared.web.ApiProblem;
import java.util.Locale;

/** Shared messages and checks for HRMS records (D22). */
final class HrRecords {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String ARCHIVED = "This record is archived.";
    static final String FORBIDDEN = "You do not have permission to perform this action.";

    private HrRecords() {}

    static void checkVersion(long current, Long requested) {
        if (requested == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (current != requested) {
            throw ApiProblem.conflict(STALE);
        }
    }

    static void requireNotArchived(boolean archived) {
        if (archived) {
            throw ApiProblem.conflict(ARCHIVED);
        }
    }

    /** Trimmed, inner whitespace collapsed (names and titles). */
    static String collapse(String value) {
        return value.replaceAll("\\s+", " ");
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
