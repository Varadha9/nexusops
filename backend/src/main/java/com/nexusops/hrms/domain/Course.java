package com.nexusops.hrms.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "courses")
public class Course extends TenantOwnedEntity {

    @Column(nullable = false)
    private String title;

    private String description;

    @Column(name = "duration_hours")
    private BigDecimal durationHours;

    private String link;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Course() {}

    public Course(UUID id, String title, String description, BigDecimal durationHours, String link) {
        super(id);
        this.createdAt = Instant.now();
        apply(title, description, durationHours, link);
    }

    public void apply(String newTitle, String newDescription, BigDecimal newDurationHours, String newLink) {
        this.title = newTitle;
        this.description = newDescription;
        this.durationHours = newDurationHours;
        this.link = newLink;
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

    public String getDescription() {
        return description;
    }

    public BigDecimal getDurationHours() {
        return durationHours;
    }

    public String getLink() {
        return link;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getVersion() {
        return version;
    }
}
