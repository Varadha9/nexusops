package com.nexusops.hrms;

import java.util.UUID;

/** Raw input: one required skill of a job role. */
public record RequiredSkill(UUID skillId, Integer minimumLevel) {}
