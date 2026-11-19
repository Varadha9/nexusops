package com.nexusops.shared.knowledge;

import java.util.UUID;

/**
 * A published document the AI knowledge index may chunk and retrieve (D4). {@code requiredPermission} is the
 * permission a caller needs to have it retrieved for them (D2).
 */
public record KnowledgeDocument(String sourceType, UUID sourceId, String title, String body,
        String requiredPermission) {}
