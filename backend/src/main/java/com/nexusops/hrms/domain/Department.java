package com.nexusops.hrms.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "departments")
public class Department extends TenantOwnedEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    private String description;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Department() {}

    public Department(UUID id, String name, String nameKey, String description) {
        super(id);
        this.createdAt = Instant.now();
        apply(name, nameKey, description);
    }

    public void apply(String newName, String newKey, String newDescription) {
        this.name = newName;
        this.nameKey = newKey;
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

    public String getName() {
        return name;
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
