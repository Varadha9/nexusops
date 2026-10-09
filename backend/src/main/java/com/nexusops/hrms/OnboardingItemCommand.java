package com.nexusops.hrms;

public record OnboardingItemCommand(String title, String description, OnboardingOwner owner, Integer dueOffsetDays) {}
