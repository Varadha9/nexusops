package com.nexusops.hrms;

import java.time.Instant;
import java.util.UUID;

public record SkillView(UUID id, String name, Instant archivedAt, long version) {}
