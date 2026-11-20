package com.nexusops.hrms;

import java.util.UUID;

public record RequiredSkillView(UUID skillId, String name, int minimumLevel) {}
