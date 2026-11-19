package com.nexusops.hrms.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DepartmentRepository extends JpaRepository<Department, UUID> {

    List<Department> findByArchivedAtIsNullOrderByNameAsc();

    List<Department> findByArchivedAtIsNotNullOrderByNameAsc();

    boolean existsByNameKey(String nameKey);

    boolean existsByNameKeyAndIdNot(String nameKey, UUID id);
}
