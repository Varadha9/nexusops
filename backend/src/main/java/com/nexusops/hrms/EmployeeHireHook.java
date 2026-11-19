package com.nexusops.hrms;

interface EmployeeHireHook {

    /** Checks the hire's own references (400s) before anything is written. */
    default void validate(EmployeeCommand command) {}

    void afterHire(com.nexusops.hrms.domain.Employee employee, EmployeeCommand command);
}
