package com.nexusops.hrms.domain;

import com.nexusops.hrms.TrainingStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface TrainingAssignmentRepository extends JpaRepository<TrainingAssignment, UUID>,
        JpaSpecificationExecutor<TrainingAssignment> {

    boolean existsByCourseIdAndEmployeeIdAndStatus(UUID courseId, UUID employeeId, TrainingStatus status);

    List<TrainingAssignment> findByEmployeeIdAndStatusOrderByDueOnAsc(UUID employeeId, TrainingStatus status);

    @Query("select count(a) from TrainingAssignment a where a.status = com.nexusops.hrms.TrainingStatus.ASSIGNED "
            + "and a.dueOn < :today")
    long overdue(LocalDate today);
}
