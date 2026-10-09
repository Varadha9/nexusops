package com.nexusops.hrms;

public record OnboardingItemView(String title, String description, OnboardingOwner owner, int dueOffsetDays) {}
