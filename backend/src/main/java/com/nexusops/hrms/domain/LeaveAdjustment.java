package com.nexusops.hrms.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/** Append-only: a correction to one employee's entitlement for a type and year. */
@Entity
@Table(name = "leave_adjustments")
public class LeaveAdjustment extends TenantOwnedEntity {

    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    @Column(name = "type_id", nullable = false, updatable = false)
    private UUID typeId;

    @Column(nullable = false, updatable = false)
    private int year;

    @Column(nullable = false, updatable = false)
    private BigDecimal days;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected LeaveAdjustment() {}

    public LeaveAdjustment(UUID id, UUID employeeId, UUID typeId, int year, BigDecimal days, String reason,
            UUID createdBy, Instant createdAt) {
        super(id);
        this.employeeId = employeeId;
        this.typeId = typeId;
        this.year = year;
        this.days = days;
        this.reason = reason;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public UUID getEmployeeId() { return employeeId; }
    public UUID getTypeId() { return typeId; }
    public int getYear() { return year; }
    public BigDecimal getDays() { return days; }
    public String getReason() { return reason; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
