package com.nexusops.hrms.web;

import com.nexusops.hrms.OnboardingTemplateService;
import com.nexusops.hrms.OnboardingTemplateView;
import com.nexusops.hrms.web.HrDtos.OnboardingTemplateRequest;
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
@RequestMapping("/api/v1/hr/onboarding-templates")
class OnboardingTemplateController {

    private final OnboardingTemplateService templates;

    OnboardingTemplateController(OnboardingTemplateService templates) {
        this.templates = templates;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('hr.employee.read')")
    List<OnboardingTemplateView> list(@RequestParam(defaultValue = "false") boolean archived) {
        return templates.list(archived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    OnboardingTemplateView create(@RequestBody OnboardingTemplateRequest request) {
        return templates.create(request.command());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    OnboardingTemplateView update(@PathVariable UUID id, @RequestBody OnboardingTemplateRequest request) {
        return templates.update(id, request.command(), request.version());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    OnboardingTemplateView archive(@PathVariable UUID id) {
        return templates.archive(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    OnboardingTemplateView restore(@PathVariable UUID id) {
        return templates.restore(id);
    }
}
