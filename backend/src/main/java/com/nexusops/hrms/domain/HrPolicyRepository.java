package com.nexusops.hrms.domain;

import com.nexusops.hrms.PolicyStatus;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HrPolicyRepository extends JpaRepository<HrPolicy, UUID> {

    List<HrPolicy> findByStatusOrderById(PolicyStatus status);
}
