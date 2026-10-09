package com.nexusops.hrms.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRoleRepository extends JpaRepository<JobRole, UUID> {

    List<JobRole> findByArchivedAtIsNullOrderByTitleAsc();

    List<JobRole> findByArchivedAtIsNotNullOrderByTitleAsc();

    boolean existsByTitleKey(String titleKey);

    boolean existsByTitleKeyAndIdNot(String titleKey, UUID id);
}
