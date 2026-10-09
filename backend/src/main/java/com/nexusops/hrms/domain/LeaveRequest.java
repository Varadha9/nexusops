package com.nexusops.hrms.domain;

import com.nexusops.hrms.LeaveStatus;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "leave_requests")
public class LeaveRequest extends TenantOwnedEntity {

    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    @Column(name = "type_id", nullable = false, updatable = false)
    private UUID typeId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private int days;

    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeaveStatus status;

    @Column(name = "requested_by", updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note")
    private String decisionNote;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected LeaveRequest() {}

    public LeaveRequest(UUID id, UUID employeeId, UUID typeId, LocalDate startDate, LocalDate endDate, int days,
            String reason, UUID requestedBy) {
        super(id);
        this.employeeId = employeeId;
        this.typeId = typeId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.days = days;
        this.reason = reason;
        this.status = LeaveStatus.PENDING;
        this.requestedBy = requestedBy;
        this.requestedAt = Instant.now();
        this.updatedAt = requestedAt;
    }

    public void decide(LeaveStatus outcome, UUID by, String note) {
        this.status = outcome;
        this.decidedBy = by;
        this.decidedAt = Instant.now();
        this.decisionNote = note;
        this.updatedAt = decidedAt;
    }

    public void cancel(UUID by) {
        this.status = LeaveStatus.CANCELLED;
        this.cancelledBy = by;
        this.cancelledAt = Instant.now();
        this.updatedAt = cancelledAt;
    }

    public UUID getEmployeeId() { return employeeId; }
    public UUID getTypeId() { return typeId; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public int getDays() { return days; }
    public String getReason() { return reason; }
    public LeaveStatus getStatus() { return status; }
    public UUID getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
    public Instant getCancelledAt() { return cancelledAt; }
    public long getVersion() { return version; }
}
