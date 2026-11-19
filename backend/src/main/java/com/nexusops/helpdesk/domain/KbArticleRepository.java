package com.nexusops.helpdesk.domain;

import com.nexusops.helpdesk.ArticleStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface KbArticleRepository extends JpaRepository<KbArticle, UUID>, JpaSpecificationExecutor<KbArticle> {

    List<KbArticle> findByStatusOrderById(ArticleStatus status);
}
