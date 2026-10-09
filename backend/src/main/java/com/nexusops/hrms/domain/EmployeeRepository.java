package com.nexusops.hrms.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeRepository extends JpaRepository<Employee, UUID>, JpaSpecificationExecutor<Employee> {

    boolean existsByPersonId(UUID personId);

    Optional<Employee> findByPersonId(UUID personId);

    Optional<Employee> findByUserId(UUID userId);

    boolean existsByNumberKey(String numberKey);

    boolean existsByNumberKeyAndIdNot(String numberKey, UUID id);

    boolean existsByUserId(UUID userId);

    boolean existsByUserIdAndIdNot(UUID userId, UUID id);

    /** Everyone in the department who hasn't left by {@code today} (D5). */
    @Query("select count(e) > 0 from Employee e where e.departmentId = :departmentId "
            + "and (e.endDate is null or e.endDate >= :today)")
    boolean anyCurrentIn(UUID departmentId, LocalDate today);

    /** id → manager id, for the loop check (D2). Small per workspace. */
    @Query("select e.id, e.managerId from Employee e where e.managerId is not null")
    List<Object[]> managerPairs();
}
