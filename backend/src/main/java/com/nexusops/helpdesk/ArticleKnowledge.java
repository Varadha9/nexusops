package com.nexusops.helpdesk;

import com.nexusops.helpdesk.domain.KbArticle;
import com.nexusops.helpdesk.domain.KbArticleRepository;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.knowledge.KnowledgeDocument;
import com.nexusops.shared.knowledge.KnowledgeSource;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Published knowledge base articles for the AI knowledge index (D4). Readers need helpdesk.article.read. */
@Component
class ArticleKnowledge implements KnowledgeSource {

    static final String SOURCE_TYPE = "KB_ARTICLE";

    private final KbArticleRepository articles;

    ArticleKnowledge(KbArticleRepository articles) {
        this.articles = articles;
    }

    @Override
    public String sourceType() {
        return SOURCE_TYPE;
    }

    @Override
    @Transactional(readOnly = true)
    public List<KnowledgeDocument> published() {
        TenantContext.requireTenantId();
        return articles.findByStatusOrderById(ArticleStatus.PUBLISHED).stream().map(ArticleKnowledge::document)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<KnowledgeDocument> findPublished(UUID id) {
        TenantContext.requireTenantId();
        return articles.findById(id).filter(a -> a.getStatus() == ArticleStatus.PUBLISHED)
                .map(ArticleKnowledge::document);
    }

    private static KnowledgeDocument document(KbArticle a) {
        return new KnowledgeDocument(SOURCE_TYPE, a.getId(), a.getTitle(), a.getBody(),
                HelpDeskPermissions.ARTICLE_READ);
    }
}
