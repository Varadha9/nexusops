package com.nexusops.hrms;

import java.time.Instant;
import java.util.UUID;

/** {@code excerpt}: the first 200 characters of the body, on one line. */
public record PolicySummary(UUID id, String title, String excerpt, PolicyStatus status, Instant publishedAt,
        Instant updatedAt) {}
