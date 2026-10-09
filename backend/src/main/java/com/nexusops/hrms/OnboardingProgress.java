package com.nexusops.hrms;

import java.util.UUID;

/** Onboarding checklist progress (D10); filled in by Task 3. */
public record OnboardingProgress(UUID templateId, String templateName, int done, int total) {}
