package com.nexusops.hrms.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillRepository extends JpaRepository<Skill, UUID> {

    List<Skill> findByArchivedAtIsNullOrderByNameAsc();

    List<Skill> findByArchivedAtIsNotNullOrderByNameAsc();

    boolean existsByNameKey(String nameKey);

    boolean existsByNameKeyAndIdNot(String nameKey, UUID id);
}
