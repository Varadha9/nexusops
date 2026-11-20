package com.nexusops.hrms;

import java.util.List;

public record OnboardingTemplateCommand(String name, List<OnboardingItemCommand> items) {}
