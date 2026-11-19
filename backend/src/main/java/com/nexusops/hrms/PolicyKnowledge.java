package com.nexusops.hrms;

import com.nexusops.hrms.domain.HrPolicy;
import com.nexusops.hrms.domain.HrPolicyRepository;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.knowledge.KnowledgeDocument;
import com.nexusops.shared.knowledge.KnowledgeSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Published HR policies for the AI knowledge index (D4). Stored as needing hr.self.service; retrieval also lets
 * hr.policy.manage through (D2).
 */
@Component
class PolicyKnowledge implements KnowledgeSource {

    static final String SOURCE_TYPE = "HR_POLICY";

    private final HrPolicyRepository policies;

    PolicyKnowledge(HrPolicyRepository policies) {
        this.policies = policies;
    }

    @Override
    public String sourceType() {
        return SOURCE_TYPE;
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeDocument> published() {
        TenantContext.requireTenantId();
        return policies.findByStatusOrderById(PolicyStatus.PUBLISHED).stream().map(PolicyKnowledge::document)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<KnowledgeDocument> findPublished(UUID id) {
        TenantContext.requireTenantId();
        return policies.findById(id).filter(p -> p.getStatus() == PolicyStatus.PUBLISHED)
                .map(PolicyKnowledge::document);
    }

    private static KnowledgeDocument document(HrPolicy p) {
        return new KnowledgeDocument(SOURCE_TYPE, p.getId(), p.getTitle(), p.getBody(), HrPermissions.SELF_SERVICE);
    }
}
