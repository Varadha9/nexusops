package com.nexusops.hrms.domain;

import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface HrPolicyRepository extends JpaRepository<HrPolicy, UUID> {}
