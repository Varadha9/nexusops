package com.nexusops.hrms.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CourseRepository extends JpaRepository<Course, UUID> {

    List<Course> findByArchivedAtIsNullOrderByTitleAsc();

    List<Course> findByArchivedAtIsNotNullOrderByTitleAsc();
}
