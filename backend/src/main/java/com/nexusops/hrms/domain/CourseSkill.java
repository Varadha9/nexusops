package com.nexusops.hrms.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** One skill a course teaches, and the level it brings the learner to; replaced as a set when the course is edited. */
@Entity
@Table(name = "course_skills")
public class CourseSkill extends TenantOwnedEntity {

    @Column(name = "course_id", nullable = false, updatable = false)
    private UUID courseId;

    @Column(name = "skill_id", nullable = false, updatable = false)
    private UUID skillId;

    @Column(nullable = false, updatable = false)
    private int level;

    protected CourseSkill() {}

    public CourseSkill(UUID id, UUID courseId, UUID skillId, int level) {
        super(id);
        this.courseId = courseId;
        this.skillId = skillId;
        this.level = level;
    }

    public UUID getCourseId() {
        return courseId;
    }

    public UUID getSkillId() {
        return skillId;
    }

    public int getLevel() {
        return level;
    }
}
