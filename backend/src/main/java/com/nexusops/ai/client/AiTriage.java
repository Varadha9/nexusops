package com.nexusops.ai.client;

/** ai-service's triage suggestion; {@code categoryId} and {@code priority} are still to be validated (D8). */
public record AiTriage(String categoryId, String priority, String reason, String mode, String model,
        String promptVersion, int inputTokens, int outputTokens) {}
