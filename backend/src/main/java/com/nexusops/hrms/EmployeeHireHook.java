package com.nexusops.hrms;

interface EmployeeHireHook {
    void afterHire(com.nexusops.hrms.domain.Employee employee, EmployeeCommand command);
}
