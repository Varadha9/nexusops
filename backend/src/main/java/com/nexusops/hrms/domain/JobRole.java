package com.nexusops.hrms.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "job_roles")
public class JobRole extends TenantOwnedEntity {

    @Column(nullable = false)
    private String title;

    @Column(name = "title_key", nullable = false)
    private String titleKey;

    @Column(name = "department_id")
    private UUID departmentId;

    private String description;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected JobRole() {}

    public JobRole(UUID id, String title, String titleKey, UUID departmentId, String description) {
        super(id);
        this.createdAt = Instant.now();
        apply(title, titleKey, departmentId, description);
    }

    public void apply(String newTitle, String newKey, UUID newDepartmentId, String newDescription) {
        this.title = newTitle;
        this.titleKey = newKey;
        this.departmentId = newDepartmentId;
        this.description = newDescription;
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

    public String getTitle() {
        return title;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public String getDescription() {
        return description;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getVersion() {
        return version;
    }
}
