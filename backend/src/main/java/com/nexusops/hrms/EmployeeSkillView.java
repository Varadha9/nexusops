package com.nexusops.hrms;

import java.util.UUID;

public record EmployeeSkillView(UUID skillId, String name, int level, String note) {}
