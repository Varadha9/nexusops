package com.nexusops.hrms.web;

import com.nexusops.collaboration.AssigneeView;
import com.nexusops.hrms.EmployeeService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/members")
class HrMemberController {

    private final EmployeeService employees;

    HrMemberController(EmployeeService employees) {
        this.employees = employees;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('hr.employee.update')")
    List<AssigneeView> members(@RequestParam(required = false) String q) {
        return employees.members(q);
    }
}
