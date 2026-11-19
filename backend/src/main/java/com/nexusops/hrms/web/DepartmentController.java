package com.nexusops.hrms.web;

import com.nexusops.hrms.DepartmentService;
import com.nexusops.hrms.DepartmentView;
import com.nexusops.hrms.web.HrDtos.DepartmentRequest;
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
@RequestMapping("/api/v1/hr/departments")
class DepartmentController {

    private final DepartmentService departments;

    DepartmentController(DepartmentService departments) {
        this.departments = departments;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('hr.employee.read')")
    List<DepartmentView> list(@RequestParam(defaultValue = "false") boolean archived) {
        return departments.list(archived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    DepartmentView create(@RequestBody DepartmentRequest request) {
        return departments.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    DepartmentView update(@PathVariable UUID id, @RequestBody DepartmentRequest request) {
        return departments.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    DepartmentView archive(@PathVariable UUID id) {
        return departments.archive(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    DepartmentView restore(@PathVariable UUID id) {
        return departments.restore(id);
    }
}
