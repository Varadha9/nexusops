package com.nexusops.hrms.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One employee's onboarding (D10): which template it came from; its tasks are in employee_onboarding_tasks. */
@Entity
@Table(name = "employee_onboardings")
public class EmployeeOnboarding extends TenantOwnedEntity {

    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Column(name = "template_name", nullable = false, updatable = false)
    private String templateName;

    @Column(name = "started_by", updatable = false)
    private UUID startedBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    protected EmployeeOnboarding() {}

    public EmployeeOnboarding(UUID id, UUID employeeId, UUID templateId, String templateName, UUID startedBy) {
        super(id);
        this.employeeId = employeeId;
        this.templateId = templateId;
        this.templateName = templateName;
        this.startedBy = startedBy;
        this.startedAt = Instant.now();
    }

    public UUID getEmployeeId() { return employeeId; }
    public UUID getTemplateId() { return templateId; }
    public String getTemplateName() { return templateName; }
}
