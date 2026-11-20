package com.nexusops.hrms;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code skills} sorted by skill name. */
public record CourseView(UUID id, String title, String description, BigDecimal durationHours, String link,
        List<TaughtSkillView> skills, Instant archivedAt, long version) {}
