package com.nexusops.hrms;

import java.time.Instant;
import java.util.UUID;

public record DepartmentView(UUID id, String name, String description, Instant archivedAt, long version) {}
