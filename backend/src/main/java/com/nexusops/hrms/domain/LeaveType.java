package com.nexusops.hrms.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "leave_types")
public class LeaveType extends TenantOwnedEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(nullable = false)
    private boolean paid;

    @Column(name = "annual_allowance")
    private Integer annualAllowance;

    @Column(nullable = false, updatable = false)
    private int position;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected LeaveType() {}

    public LeaveType(UUID id, String name, String nameKey, boolean paid, Integer annualAllowance, int position) {
        super(id);
        this.position = position;
        this.createdAt = Instant.now();
        apply(name, nameKey, paid, annualAllowance);
    }

    public void apply(String newName, String newKey, boolean newPaid, Integer newAllowance) {
        this.name = newName;
        this.nameKey = newKey;
        this.paid = newPaid;
        this.annualAllowance = newAllowance;
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

    public boolean isPaid() {
        return paid;
    }

    public Integer getAnnualAllowance() {
        return annualAllowance;
    }

    public int getPosition() {
        return position;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getVersion() {
        return version;
    }
}
