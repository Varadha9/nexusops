package com.nexusops.hrms;

import com.nexusops.tenancy.WorkspaceRegistered;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Every new workspace starts with the HRMS defaults, in the signup transaction (D10; Task 4 adds leave types). */
@Component
class HrSetup {

    private final OnboardingTemplateService templates;

    HrSetup(OnboardingTemplateService templates) {
        this.templates = templates;
    }

    @EventListener
    void on(WorkspaceRegistered event) {
        templates.seedDefaults();
    }
}
