package com.nexusops.hrms.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface LeaveTypeRepository extends JpaRepository<LeaveType, UUID> {

    List<LeaveType> findByArchivedAtIsNullOrderByPositionAscNameAsc();

    List<LeaveType> findByArchivedAtIsNotNullOrderByPositionAscNameAsc();

    boolean existsByNameKey(String nameKey);

    boolean existsByNameKeyAndIdNot(String nameKey, UUID id);
}
