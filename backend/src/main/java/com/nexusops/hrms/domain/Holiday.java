package com.nexusops.hrms.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** A public holiday: added or removed, never edited. */
@Entity
@Table(name = "holidays")
public class Holiday extends TenantOwnedEntity {

    @Column(name = "day", nullable = false, updatable = false)
    private LocalDate date;

    @Column(nullable = false, updatable = false)
    private String name;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected Holiday() {}

    public Holiday(UUID id, LocalDate date, String name) {
        super(id);
        this.date = date;
        this.name = name;
        this.createdAt = Instant.now();
    }

    public LocalDate getDate() {
        return date;
    }

    public String getName() {
        return name;
    }
}
