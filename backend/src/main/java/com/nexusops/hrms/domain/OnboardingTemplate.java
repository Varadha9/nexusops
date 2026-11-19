package com.nexusops.hrms.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** An onboarding checklist template (D10); its items are replaced as a set when it is edited. */
@Entity
@Table(name = "onboarding_templates")
public class OnboardingTemplate extends TenantOwnedEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected OnboardingTemplate() {}

    public OnboardingTemplate(UUID id, String name, String nameKey) {
        super(id);
        this.createdAt = Instant.now();
        apply(name, nameKey);
    }

    public void apply(String newName, String newKey) {
        this.name = newName;
        this.nameKey = newKey;
        this.updatedAt = Instant.now();
    }

    /** Marks the template changed (its items were replaced), which bumps the version. */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    public void archive(Instant now) {
        this.archivedAt = now;
        this.updatedAt = now;
    }

    public void restore() {
        this.archivedAt = null;
        this.updatedAt = Instant.now();
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public String getName() {
        return name;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getVersion() {
        return version;
    }
}
