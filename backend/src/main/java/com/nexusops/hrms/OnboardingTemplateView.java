package com.nexusops.hrms;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record OnboardingTemplateView(UUID id, String name, List<OnboardingItemView> items, Instant archivedAt,
        long version) {}
