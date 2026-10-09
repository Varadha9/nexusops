package com.nexusops.hrms.web;

import com.nexusops.hrms.JobRoleService;
import com.nexusops.hrms.JobRoleView;
import com.nexusops.hrms.web.HrDtos.JobRoleRequest;
import java.util.List;
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
@RequestMapping("/api/v1/hr/job-roles")
class JobRoleController {

    private final JobRoleService roles;

    JobRoleController(JobRoleService roles) {
        this.roles = roles;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('hr.employee.read')")
    List<JobRoleView> list(@RequestParam(defaultValue = "false") boolean archived,
            @RequestParam(required = false) UUID departmentId) {
        return roles.list(archived, departmentId);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('hr.employee.read')")
    JobRoleView get(@PathVariable UUID id) {
        return roles.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    JobRoleView create(@RequestBody JobRoleRequest request) {
        return roles.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    JobRoleView update(@PathVariable UUID id, @RequestBody JobRoleRequest request) {
        return roles.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    JobRoleView archive(@PathVariable UUID id) {
        return roles.archive(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    JobRoleView restore(@PathVariable UUID id) {
        return roles.restore(id);
    }
}
