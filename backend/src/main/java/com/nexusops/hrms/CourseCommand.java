package com.nexusops.hrms;

import java.math.BigDecimal;
import java.util.List;

public record CourseCommand(String title, String description, BigDecimal durationHours, String link,
        List<TaughtSkill> skills) {}
