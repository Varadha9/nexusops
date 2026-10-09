package com.nexusops.hrms.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** One required skill of a job role (D6); replaced as a set when the role is edited. */
@Entity
@Table(name = "job_role_skills")
public class JobRoleSkill extends TenantOwnedEntity {

    @Column(name = "job_role_id", nullable = false, updatable = false)
    private UUID jobRoleId;

    @Column(name = "skill_id", nullable = false, updatable = false)
    private UUID skillId;

    @Column(name = "minimum_level", nullable = false, updatable = false)
    private int minimumLevel;

    protected JobRoleSkill() {}

    public JobRoleSkill(UUID id, UUID jobRoleId, UUID skillId, int minimumLevel) {
        super(id);
        this.jobRoleId = jobRoleId;
        this.skillId = skillId;
        this.minimumLevel = minimumLevel;
    }

    public UUID getJobRoleId() {
        return jobRoleId;
    }

    public UUID getSkillId() {
        return skillId;
    }

    public int getMinimumLevel() {
        return minimumLevel;
    }
}
