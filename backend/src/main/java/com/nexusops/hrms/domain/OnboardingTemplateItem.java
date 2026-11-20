package com.nexusops.hrms.domain;

import com.nexusops.hrms.OnboardingOwner;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import java.util.UUID;

/** One checklist item of an onboarding template (D10); replaced as a set when the template is edited. */
@Entity
@Table(name = "onboarding_template_items")
public class OnboardingTemplateItem extends TenantOwnedEntity {

    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Column(nullable = false, updatable = false)
    private int position;

    @Column(nullable = false, updatable = false)
    private String title;

    @Column(updatable = false)
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false)
    private OnboardingOwner owner;

    @Column(name = "due_offset_days", nullable = false, updatable = false)
    private int dueOffsetDays;

    protected OnboardingTemplateItem() {}

    public OnboardingTemplateItem(UUID id, UUID templateId, int position, String title, String description,
            OnboardingOwner owner, int dueOffsetDays) {
        super(id);
        this.templateId = templateId;
        this.position = position;
        this.title = title;
        this.description = description;
        this.owner = owner;
        this.dueOffsetDays = dueOffsetDays;
    }

    public UUID getTemplateId() {
        return templateId;
    }

    public int getPosition() {
        return position;
    }

    public String getTitle() {
        return title;
    }

    public String getDescription() {
        return description;
    }

    public OnboardingOwner getOwner() {
        return owner;
    }

    public int getDueOffsetDays() {
        return dueOffsetDays;
    }
}
