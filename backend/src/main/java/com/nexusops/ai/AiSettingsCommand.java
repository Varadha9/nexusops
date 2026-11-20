package com.nexusops.ai;

/** A change to the workspace's AI settings (D9); validated by {@link AiSettingsService}. */
public record AiSettingsCommand(Boolean enabled, Integer monthlyLimit) {}
