package com.nexusops.hrms.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OnboardingTemplateRepository extends JpaRepository<OnboardingTemplate, UUID> {

    List<OnboardingTemplate> findByArchivedAtIsNullOrderByNameAsc();

    List<OnboardingTemplate> findByArchivedAtIsNotNullOrderByNameAsc();

    boolean existsByNameKey(String nameKey);

    boolean existsByNameKeyAndIdNot(String nameKey, UUID id);
}
