package com.nexusops.ai.client;

import java.util.List;

/**
 * ai-service's answer; {@code citedPassages} are passage numbers, still to be checked against what was sent (D8).
 * {@code grounded} is true only when the answer is made of passage sentences, not a "nothing found" or "these may help"
 * message.
 */
public record AiAnswer(String answer, List<Integer> citedPassages, boolean grounded, String mode, String model,
        String promptVersion, int inputTokens, int outputTokens) {}
