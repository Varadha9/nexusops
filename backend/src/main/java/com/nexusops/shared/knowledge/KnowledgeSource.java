package com.nexusops.shared.knowledge;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Implemented by a business module that owns indexable documents (D4). Reads run for the current tenant (RLS) and
 * without permission checks: the only caller is the knowledge indexer, which stores each document's required
 * permission and filters on it at retrieval time.
 */
public interface KnowledgeSource {

    /** The {@code ai_chunks.source_type} this bean serves, e.g. {@code KB_ARTICLE}. */
    String sourceType();

    /** The current tenant's PUBLISHED documents. */
    List<KnowledgeDocument> published();

    /** The document, or empty unless it exists and is PUBLISHED. */
    Optional<KnowledgeDocument> findPublished(UUID id);
}
