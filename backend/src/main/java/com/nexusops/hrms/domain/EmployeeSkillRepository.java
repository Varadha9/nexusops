package com.nexusops.hrms.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeSkillRepository extends JpaRepository<EmployeeSkill, UUID> {

    List<EmployeeSkill> findByEmployeeId(UUID employeeId);

    List<EmployeeSkill> findByEmployeeIdIn(Collection<UUID> employeeIds);

    Optional<EmployeeSkill> findByEmployeeIdAndSkillId(UUID employeeId, UUID skillId);

    @Modifying
    @Query("delete from EmployeeSkill s where s.employeeId = :employeeId")
    void deleteByEmployee(UUID employeeId);
}
