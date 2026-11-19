package com.nexusops.ai.client;

import java.util.List;

/** ai-service's answer; {@code citedPassages} are passage numbers, still to be checked against what was sent (D8). */
public record AiAnswer(String answer, List<Integer> citedPassages, String mode, String model, String promptVersion,
        int inputTokens, int outputTokens) {}
