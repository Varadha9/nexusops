package com.nexusops.directory;

import java.util.UUID;

/**
 * Implemented by a module that owns employment (HRMS, D3): the directory asks before anyone edits a person's EMPLOYEE
 * role by hand, so the role can't drift from the employment record. Throw an ApiProblem to refuse.
 */
public interface EmploymentGuard {

    void beforeEmployeeRoleChange(UUID personId);
}
