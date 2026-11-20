package com.nexusops.ai;

/** The workspace's AI settings with this month's usage (UTC), the provider status and the index size. */
public record AiSettingsView(boolean enabled, int monthlyLimit, long usedThisMonth, String provider,
        long indexedChunks, long version) {}
