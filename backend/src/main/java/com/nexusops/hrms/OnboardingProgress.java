package com.nexusops.hrms;

import java.util.UUID;

/** Onboarding checklist progress (D10); done and total count the tasks that are not cancelled. */
public record OnboardingProgress(UUID templateId, String templateName, int done, int total) {}
