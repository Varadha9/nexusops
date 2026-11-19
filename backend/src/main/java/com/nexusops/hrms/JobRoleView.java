package com.nexusops.hrms;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code requiredSkills} sorted by skill name. */
public record JobRoleView(UUID id, String title, DepartmentRef department, String description,
        List<RequiredSkillView> requiredSkills, Instant archivedAt, long version) {}
