package com.nexusops.hrms;

import com.nexusops.tenancy.WorkspaceRegistered;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Every new workspace starts with the HRMS defaults, in the signup transaction (D10, D11). */
@Component
class HrSetup {

    private final OnboardingTemplateService templates;
    private final LeaveTypeService leaveTypes;

    HrSetup(OnboardingTemplateService templates, LeaveTypeService leaveTypes) {
        this.templates = templates;
        this.leaveTypes = leaveTypes;
    }

    @EventListener
    void on(WorkspaceRegistered event) {
        templates.seedDefaults();
        leaveTypes.seedDefaults();
    }
}
