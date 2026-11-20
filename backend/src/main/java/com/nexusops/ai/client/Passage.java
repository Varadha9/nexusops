package com.nexusops.ai.client;

/** A numbered, delimited reference passage sent to ai-service (D8); {@code n} starts at 1. */
public record Passage(int n, String title, String text) {}
