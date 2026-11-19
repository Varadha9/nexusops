package com.nexusops.hrms.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface OnboardingTemplateItemRepository extends JpaRepository<OnboardingTemplateItem, UUID> {

    List<OnboardingTemplateItem> findByTemplateIdOrderByPositionAsc(UUID templateId);

    List<OnboardingTemplateItem> findByTemplateIdInOrderByPositionAsc(Collection<UUID> templateIds);

    @Modifying
    @Query("delete from OnboardingTemplateItem i where i.templateId = :templateId")
    void deleteByTemplate(UUID templateId);
}
