package com.nexusops.hrms;

import com.nexusops.directory.EmploymentGuard;
import com.nexusops.hrms.domain.EmployeeRepository;
import com.nexusops.shared.web.ApiProblem;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** D3: once HRMS keeps someone's employment, the directory can't edit their EMPLOYEE role by hand. */
@Component
class HrmsEmploymentGuard implements EmploymentGuard {

    static final String MANAGED = "This person's employment is managed in HRMS.";

    private final EmployeeRepository employees;

    HrmsEmploymentGuard(EmployeeRepository employees) {
        this.employees = employees;
    }

    @Override
    public void beforeEmployeeRoleChange(UUID personId) {
        if (employees.existsByPersonId(personId)) {
            throw ApiProblem.conflict(MANAGED);
        }
    }
}
