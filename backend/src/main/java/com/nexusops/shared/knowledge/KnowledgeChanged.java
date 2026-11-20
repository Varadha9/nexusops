package com.nexusops.shared.knowledge;

import java.util.UUID;

/**
 * Published inside the transaction that edits, publishes, unpublishes or archives a knowledge document (D3), so the
 * index can drop its chunks in that same transaction and re-embed it after commit.
 */
public record KnowledgeChanged(String sourceType, UUID sourceId) {}
