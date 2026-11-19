package com.nexusops.hrms;

import java.util.List;
import java.util.UUID;

public record JobRoleCommand(String title, UUID departmentId, String description, List<RequiredSkill> requiredSkills) {}
