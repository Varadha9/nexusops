package com.nexusops.hrms.domain;

import com.nexusops.hrms.PolicyStatus;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

/** An HR policy. The generated {@code search} column is not mapped. */
@Entity
@Table(name = "hr_policies")
public class HrPolicy extends TenantOwnedEntity {

    @Column(nullable = false)
    private String title;

    @Column(nullable = false)
    private String body;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PolicyStatus status;

    @Column(name = "author_id", updatable = false)
    private UUID authorId;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected HrPolicy() {}

    public HrPolicy(UUID id, String title, String body, UUID authorId) {
        super(id);
        this.status = PolicyStatus.DRAFT;
        this.authorId = authorId;
        this.createdAt = Instant.now();
        apply(title, body);
    }

    public void apply(String newTitle, String newBody) {
        this.title = newTitle;
        this.body = newBody;
        this.updatedAt = Instant.now();
    }

    public void publish(Instant now) {
        this.status = PolicyStatus.PUBLISHED;
        this.publishedAt = now;
        this.updatedAt = now;
    }

    public void unpublish(Instant now) {
        this.status = PolicyStatus.DRAFT;
        this.publishedAt = null;
        this.updatedAt = now;
    }

    public void archive(Instant now) {
        this.status = PolicyStatus.ARCHIVED;
        this.publishedAt = null;
        this.updatedAt = now;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public PolicyStatus getStatus() {
        return status;
    }

    public UUID getAuthorId() {
        return authorId;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
