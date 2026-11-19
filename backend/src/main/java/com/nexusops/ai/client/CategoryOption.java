package com.nexusops.ai.client;

import java.util.UUID;

/** A workspace ticket category ai-service may pick for triage. */
public record CategoryOption(UUID id, String name) {}
