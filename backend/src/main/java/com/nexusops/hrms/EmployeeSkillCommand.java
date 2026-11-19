package com.nexusops.hrms;

import java.util.UUID;

public record EmployeeSkillCommand(UUID skillId, Integer level, String note) {}
