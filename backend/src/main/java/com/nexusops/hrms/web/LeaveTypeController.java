package com.nexusops.hrms.web;

import com.nexusops.hrms.LeaveTypeService;
import com.nexusops.hrms.LeaveTypeView;
import com.nexusops.hrms.web.HrDtos.LeaveTypeRequest;
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
@RequestMapping("/api/v1/hr/leave-types")
class LeaveTypeController {

    private final LeaveTypeService types;

    LeaveTypeController(LeaveTypeService types) {
        this.types = types;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('hr.employee.read', 'hr.self.service')")
    List<LeaveTypeView> list(@RequestParam(defaultValue = "false") boolean archived) {
        return types.list(archived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    LeaveTypeView create(@RequestBody LeaveTypeRequest request) {
        return types.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    LeaveTypeView update(@PathVariable UUID id, @RequestBody LeaveTypeRequest request) {
        return types.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    LeaveTypeView archive(@PathVariable UUID id) {
        return types.archive(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    LeaveTypeView restore(@PathVariable UUID id) {
        return types.restore(id);
    }
}
