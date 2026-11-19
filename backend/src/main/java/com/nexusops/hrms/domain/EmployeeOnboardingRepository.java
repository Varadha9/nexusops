package com.nexusops.hrms.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeOnboardingRepository extends JpaRepository<EmployeeOnboarding, UUID> {

    Optional<EmployeeOnboarding> findByEmployeeId(UUID employeeId);

    boolean existsByEmployeeId(UUID employeeId);

    @Query(value = "select task_id from employee_onboarding_tasks where onboarding_id = :onboardingId "
            + "and tenant_id = :tenantId", nativeQuery = true)
    List<UUID> taskIds(UUID onboardingId, UUID tenantId);

    @Modifying
    @Query(value = "insert into employee_onboarding_tasks (id, tenant_id, onboarding_id, task_id) "
            + "values (:id, :tenantId, :onboardingId, :taskId)", nativeQuery = true)
    void linkTask(UUID id, UUID tenantId, UUID onboardingId, UUID taskId);
}
