/**
 * AI platform (Phase 12 part 1, ADR-0014): the gateway to the stateless ai-service and the pgvector knowledge index
 * of published documents from {@link com.nexusops.shared.knowledge.KnowledgeSource} beans. All data access stays here
 * under the tenant's RLS session (D1); ai-service only receives the text this module chooses to send. Nothing may
 * depend on this module; it may depend on HelpDesk (D4).
 */
package com.nexusops.ai;
