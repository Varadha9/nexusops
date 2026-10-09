package com.nexusops.hrms.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** One skill an employee holds, with a level from 1 to 5 (D7); replaced as a set when the skills are edited. */
@Entity
@Table(name = "employee_skills")
public class EmployeeSkill extends TenantOwnedEntity {

    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    @Column(name = "skill_id", nullable = false, updatable = false)
    private UUID skillId;

    @Column(nullable = false)
    private int level;

    @Column
    private String note;

    protected EmployeeSkill() {}

    public EmployeeSkill(UUID id, UUID employeeId, UUID skillId, int level, String note) {
        super(id);
        this.employeeId = employeeId;
        this.skillId = skillId;
        this.level = level;
        this.note = note;
    }

    /** Completing a course never lowers a level. */
    public void raise(int newLevel) {
        if (newLevel > level) {
            this.level = newLevel;
        }
    }

    public UUID getEmployeeId() {
        return employeeId;
    }

    public UUID getSkillId() {
        return skillId;
    }

    public int getLevel() {
        return level;
    }

    public String getNote() {
        return note;
    }
}
