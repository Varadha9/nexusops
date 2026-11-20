package com.nexusops.hrms.web;

import com.nexusops.hrms.LeaveRequestCommand;
import com.nexusops.hrms.LeaveRequestView;
import com.nexusops.hrms.MeView;
import com.nexusops.hrms.SelfService;
import com.nexusops.hrms.TrainingAssignmentView;
import com.nexusops.hrms.web.HrDtos.CompleteRequest;
import com.nexusops.hrms.web.HrDtos.VersionRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/me")
class SelfServiceController {

    /** Any employeeId in the body is ignored: self-service only ever acts on the caller. */
    record OwnLeaveRequest(UUID typeId, LocalDate startDate, LocalDate endDate, String reason) {}

    private final SelfService self;

    SelfServiceController(SelfService self) {
        this.self = self;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('hr.self.service')")
    MeView me() {
        return self.me();
    }

    @PostMapping("/leave-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('hr.self.service')")
    LeaveRequestView requestLeave(@RequestBody OwnLeaveRequest body) {
        return self.requestLeave(new LeaveRequestCommand(body.typeId(), body.startDate(), body.endDate(), body.reason()));
    }

    @PostMapping("/leave-requests/{id}/cancel")
    @PreAuthorize("hasAuthority('hr.self.service')")
    LeaveRequestView cancelLeave(@PathVariable UUID id, @RequestBody VersionRequest body) {
        return self.cancelLeave(id, body.version());
    }

    @PostMapping("/training/{id}/complete")
    @PreAuthorize("hasAuthority('hr.self.service')")
    TrainingAssignmentView completeTraining(@PathVariable UUID id, @RequestBody CompleteRequest body) {
        return self.completeTraining(id, body.note(), body.version());
    }
}
