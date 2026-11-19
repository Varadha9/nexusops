package com.nexusops.hrms.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface CourseSkillRepository extends JpaRepository<CourseSkill, UUID> {

    List<CourseSkill> findByCourseId(UUID courseId);

    List<CourseSkill> findByCourseIdIn(Collection<UUID> courseIds);

    @Modifying
    @Query("delete from CourseSkill s where s.courseId = :courseId")
    void deleteByCourse(UUID courseId);

    @Query("select s.courseId from CourseSkill s where s.skillId = :skillId and s.level >= :level")
    List<UUID> coursesTeaching(UUID skillId, int level);
}
