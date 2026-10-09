package com.nexusops.hrms.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface JobRoleSkillRepository extends JpaRepository<JobRoleSkill, UUID> {

    List<JobRoleSkill> findByJobRoleIdIn(Collection<UUID> jobRoleIds);

    List<JobRoleSkill> findByJobRoleId(UUID jobRoleId);

    @Modifying
    @Query("delete from JobRoleSkill s where s.jobRoleId = :jobRoleId")
    void deleteByJobRole(UUID jobRoleId);
}
