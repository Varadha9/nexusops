package com.nexusops.hrms.domain;

import com.nexusops.hrms.TrainingStatus;
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
@Table(name = "training_assignments")
public class TrainingAssignment extends TenantOwnedEntity {

    @Column(name = "course_id", nullable = false, updatable = false)
    private UUID courseId;

    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    @Column(name = "due_on")
    private LocalDate dueOn;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TrainingStatus status;

    @Column(name = "assigned_by", updatable = false)
    private UUID assignedBy;

    @Column(name = "assigned_at", nullable = false, updatable = false)
    private Instant assignedAt;

    @Column(name = "completed_on")
    private LocalDate completedOn;

    @Column(name = "completion_note")
    private String completionNote;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected TrainingAssignment() {}

    public TrainingAssignment(UUID id, UUID courseId, UUID employeeId, LocalDate dueOn, UUID assignedBy) {
        super(id);
        this.courseId = courseId;
        this.employeeId = employeeId;
        this.dueOn = dueOn;
        this.status = TrainingStatus.ASSIGNED;
        this.assignedBy = assignedBy;
        this.assignedAt = Instant.now();
        this.updatedAt = assignedAt;
    }

    public void complete(LocalDate on, String note) {
        this.status = TrainingStatus.COMPLETED;
        this.completedOn = on;
        this.completionNote = note;
        this.updatedAt = Instant.now();
    }

    public void cancel() {
        this.status = TrainingStatus.CANCELLED;
        this.cancelledAt = Instant.now();
        this.updatedAt = cancelledAt;
    }

    public UUID getCourseId() { return courseId; }
    public UUID getEmployeeId() { return employeeId; }
    public LocalDate getDueOn() { return dueOn; }
    public TrainingStatus getStatus() { return status; }
    public UUID getAssignedBy() { return assignedBy; }
    public Instant getAssignedAt() { return assignedAt; }
    public LocalDate getCompletedOn() { return completedOn; }
    public String getCompletionNote() { return completionNote; }
    public Instant getCancelledAt() { return cancelledAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
