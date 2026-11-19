package com.nexusops.ai;

import java.util.UUID;

/** A chunk the caller may read, with its cosine distance to the query (smaller is closer). */
public record RetrievedChunk(String sourceType, UUID sourceId, String title, String content, double distance) {}
