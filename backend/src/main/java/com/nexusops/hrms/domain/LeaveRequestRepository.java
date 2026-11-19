package com.nexusops.hrms.domain;

import com.nexusops.hrms.LeaveStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID>,
        JpaSpecificationExecutor<LeaveRequest> {

    @Query("select count(r) > 0 from LeaveRequest r where r.employeeId = :employeeId and r.status in :statuses "
            + "and r.startDate <= :end and r.endDate >= :start")
    boolean overlaps(UUID employeeId, Collection<LeaveStatus> statuses, LocalDate start, LocalDate end);

    @Query("select coalesce(sum(r.days), 0) from LeaveRequest r where r.employeeId = :employeeId "
            + "and r.typeId = :typeId and r.status = :status and r.startDate between :from and :to")
    long sumDays(UUID employeeId, UUID typeId, LeaveStatus status, LocalDate from, LocalDate to);

    List<LeaveRequest> findByEmployeeIdOrderByStartDateDesc(UUID employeeId);

    @Query("select r from LeaveRequest r where r.employeeId = :employeeId and r.status in :statuses "
            + "and r.startDate > :after")
    List<LeaveRequest> startingAfter(UUID employeeId, Collection<LeaveStatus> statuses, LocalDate after);

    @Query("select r from LeaveRequest r where r.status = com.nexusops.hrms.LeaveStatus.APPROVED "
            + "and r.startDate <= :to and r.endDate >= :from order by r.startDate, r.id")
    List<LeaveRequest> approvedBetween(LocalDate from, LocalDate to);

    long countByStatus(LeaveStatus status);
}
