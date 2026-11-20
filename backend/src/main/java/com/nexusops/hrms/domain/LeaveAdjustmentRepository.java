package com.nexusops.hrms.domain;

import java.math.BigDecimal;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface LeaveAdjustmentRepository extends JpaRepository<LeaveAdjustment, UUID> {

    /** null when there are none. */
    @Query("select sum(a.days) from LeaveAdjustment a where a.employeeId = :employeeId "
            + "and a.typeId = :typeId and a.year = :year")
    BigDecimal sumDays(UUID employeeId, UUID typeId, int year);
}
