package com.nexusops.ai;

/** Whether AI is on for the workspace and whether ai-service answers ({@code LOCAL} or {@code UNAVAILABLE}). */
public record AiStatusView(boolean enabled, String provider) {}
