package com.nexusops.hrms.web;

import com.nexusops.hrms.TrainingAssignmentView;
import com.nexusops.hrms.TrainingQuery;
import com.nexusops.hrms.TrainingService;
import com.nexusops.hrms.TrainingStatus;
import com.nexusops.hrms.web.HrDtos.AssignTrainingRequest;
import com.nexusops.hrms.web.HrDtos.CompleteRequest;
import com.nexusops.hrms.web.HrDtos.VersionRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
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

/** Training assignments for HR (D9); employees complete their own in their area (Task 6). */
@RestController
@RequestMapping("/api/v1/hr/training-assignments")
class TrainingController {

    private final TrainingService training;

    TrainingController(TrainingService training) {
        this.training = training;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('hr.employee.read')")
    PageResponse<TrainingAssignmentView> list(@RequestParam(required = false) UUID employeeId,
            @RequestParam(required = false) TrainingStatus status, @RequestParam(required = false) Boolean overdue,
            @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return training.list(new TrainingQuery(employeeId, status, overdue), page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('hr.training.manage')")
    TrainingAssignmentView assign(@RequestBody AssignTrainingRequest body) {
        return training.assign(body.courseId(), body.employeeId(), body.dueOn());
    }

    @PostMapping("/{id}/complete")
    @PreAuthorize("hasAuthority('hr.training.manage')")
    TrainingAssignmentView complete(@PathVariable UUID id, @RequestBody CompleteRequest body) {
        return training.complete(id, body.note(), body.version());
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('hr.training.manage')")
    TrainingAssignmentView cancel(@PathVariable UUID id, @RequestBody VersionRequest body) {
        return training.cancel(id, body.version());
    }
}
