package com.nexusops.hrms.web;

import com.nexusops.hrms.PolicyService;
import com.nexusops.hrms.PolicyStatus;
import com.nexusops.hrms.PolicySummary;
import com.nexusops.hrms.PolicyView;
import com.nexusops.hrms.web.HrDtos.PolicyRequest;
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
@RequestMapping("/api/v1/hr/policies")
class PolicyController {

    private final PolicyService policies;

    PolicyController(PolicyService policies) {
        this.policies = policies;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('hr.self.service', 'hr.policy.manage')")
    PageResponse<PolicySummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) PolicyStatus status, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return policies.list(q, status, page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('hr.self.service', 'hr.policy.manage')")
    PolicyView get(@PathVariable UUID id) {
        return policies.get(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('hr.policy.manage')")
    PolicyView create(@RequestBody PolicyRequest request) {
        return policies.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('hr.policy.manage')")
    PolicyView update(@PathVariable UUID id, @RequestBody PolicyRequest request) {
        return policies.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/publish")
    @PreAuthorize("hasAuthority('hr.policy.manage')")
    PolicyView publish(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return policies.publish(id, request.version());
    }

    @PostMapping("/{id}/unpublish")
    @PreAuthorize("hasAuthority('hr.policy.manage')")
    PolicyView unpublish(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return policies.unpublish(id, request.version());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('hr.policy.manage')")
    PolicyView archive(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return policies.archive(id, request.version());
    }
}
