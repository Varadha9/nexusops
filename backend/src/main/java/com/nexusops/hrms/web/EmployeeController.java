package com.nexusops.hrms.web;

import com.nexusops.hrms.EmployeeQuery;
import com.nexusops.hrms.EmployeeService;
import com.nexusops.hrms.EmployeeSummary;
import com.nexusops.hrms.EmployeeView;
import com.nexusops.hrms.EmploymentStatus;
import com.nexusops.hrms.EndEmployment;
import com.nexusops.hrms.SkillGapView;
import com.nexusops.hrms.web.HrDtos.EmployeeRequest;
import com.nexusops.hrms.web.HrDtos.EndRequest;
import com.nexusops.hrms.web.HrDtos.SkillsRequest;
import com.nexusops.hrms.web.HrDtos.VersionRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/employees")
class EmployeeController {

    private final EmployeeService employees;

    EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('hr.employee.read')")
    PageResponse<EmployeeSummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) EmploymentStatus status, @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID jobRoleId, @RequestParam(required = false) UUID managerId,
            @RequestParam(required = false) UUID personId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return employees.list(new EmployeeQuery(q, status, departmentId, jobRoleId, managerId, personId), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('hr.employee.read')")
    EmployeeView get(@PathVariable UUID id) {
        return employees.get(id);
    }

    @GetMapping("/{id}/skill-gap")
    @PreAuthorize("hasAuthority('hr.employee.read')")
    SkillGapView skillGap(@PathVariable UUID id) {
        return employees.skillGap(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('hr.employee.update')")
    EmployeeView hire(@RequestBody EmployeeRequest request) {
        return employees.hire(request.hire());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('hr.employee.update')")
    EmployeeView update(@PathVariable UUID id, @RequestBody EmployeeRequest request) {
        return employees.update(id, request.update(), request.version());
    }

    @PostMapping("/{id}/end")
    @PreAuthorize("hasAuthority('hr.employee.update')")
    EmployeeView end(@PathVariable UUID id, @RequestBody EndRequest request) {
        return employees.end(id, new EndEmployment(request.endDate(), request.reason()), request.version());
    }

    @PostMapping("/{id}/reinstate")
    @PreAuthorize("hasAuthority('hr.employee.update')")
    EmployeeView reinstate(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return employees.reinstate(id, request.version());
    }

    @PutMapping("/{id}/skills")
    @PreAuthorize("hasAuthority('hr.employee.update')")
    EmployeeView skills(@PathVariable UUID id, @RequestBody SkillsRequest request) {
        return employees.setSkills(id, request.skills(), request.version());
    }
}
