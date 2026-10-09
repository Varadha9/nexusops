package com.nexusops.hrms.web;

import com.nexusops.hrms.AwayView;
import com.nexusops.hrms.LeaveQuery;
import com.nexusops.hrms.LeaveRequestView;
import com.nexusops.hrms.LeaveService;
import com.nexusops.hrms.LeaveStatus;
import com.nexusops.hrms.web.HrDtos.DecisionRequest;
import com.nexusops.hrms.web.HrDtos.LeaveRequestRequest;
import com.nexusops.hrms.web.HrDtos.VersionRequest;
import com.nexusops.shared.web.PageResponse;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Leave requests for approvers (D13); employees use their own area (Task 6). */
@RestController
@RequestMapping("/api/v1/hr")
class LeaveController {

    private final LeaveService leave;

    LeaveController(LeaveService leave) {
        this.leave = leave;
    }

    @GetMapping("/leave-requests")
    @PreAuthorize("hasAuthority('hr.leave.approve')")
    PageResponse<LeaveRequestView> list(@RequestParam(required = false) LeaveStatus status,
            @RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return leave.list(new LeaveQuery(status, employeeId, from, to), page, size);
    }

    @PostMapping("/leave-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('hr.leave.approve')")
    LeaveRequestView request(@RequestBody LeaveRequestRequest body) {
        return leave.request(body.employeeId(), body.command());
    }

    @PostMapping("/leave-requests/{id}/approve")
    @PreAuthorize("hasAuthority('hr.leave.approve')")
    LeaveRequestView approve(@PathVariable UUID id, @RequestBody DecisionRequest body) {
        return leave.approve(id, body.note(), body.version());
    }

    @PostMapping("/leave-requests/{id}/reject")
    @PreAuthorize("hasAuthority('hr.leave.approve')")
    LeaveRequestView reject(@PathVariable UUID id, @RequestBody DecisionRequest body) {
        return leave.reject(id, body.note(), body.version());
    }

    @PostMapping("/leave-requests/{id}/cancel")
    @PreAuthorize("hasAuthority('hr.leave.approve')")
    LeaveRequestView cancel(@PathVariable UUID id, @RequestBody VersionRequest body) {
        return leave.cancel(id, body.version());
    }

    @GetMapping("/leave/away")
    @PreAuthorize("hasAuthority('hr.leave.approve')")
    List<AwayView> away(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return leave.away(from, to);
    }
}
