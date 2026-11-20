package com.nexusops.ai;

import java.util.UUID;

/** A document an AI answer was drawn from; always one of the passages retrieved for the caller (D8). */
public record AiCitation(String sourceType, UUID sourceId, String title) {}
