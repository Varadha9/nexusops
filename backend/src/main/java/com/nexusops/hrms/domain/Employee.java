package com.nexusops.hrms.domain;

import com.nexusops.hrms.EmploymentType;
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
@Table(name = "employees")
public class Employee extends TenantOwnedEntity {

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Column(name = "employee_number", nullable = false)
    private String employeeNumber;

    @Column(name = "number_key", nullable = false)
    private String numberKey;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "job_role_id")
    private UUID jobRoleId;

    @Column(name = "manager_id")
    private UUID managerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", nullable = false)
    private EmploymentType employmentType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "end_reason")
    private String endReason;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Employee() {}

    public Employee(UUID id, UUID personId) {
        super(id);
        this.personId = personId;
        this.createdAt = Instant.now();
    }

    public void apply(String number, String numberKey, UUID departmentId, UUID jobRoleId, UUID managerId,
            EmploymentType type, LocalDate startDate, UUID userId) {
        this.employeeNumber = number;
        this.numberKey = numberKey;
        this.departmentId = departmentId;
        this.jobRoleId = jobRoleId;
        this.managerId = managerId;
        this.employmentType = type;
        this.startDate = startDate;
        this.userId = userId;
        this.updatedAt = Instant.now();
    }

    public void end(LocalDate date, String reason) {
        this.endDate = date;
        this.endReason = reason;
        this.updatedAt = Instant.now();
    }

    public void reinstate() {
        this.endDate = null;
        this.endReason = null;
        this.updatedAt = Instant.now();
    }

    /** Bumps the version for changes kept in child tables (skills). */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getPersonId() { return personId; }
    public String getEmployeeNumber() { return employeeNumber; }
    public UUID getDepartmentId() { return departmentId; }
    public UUID getJobRoleId() { return jobRoleId; }
    public UUID getManagerId() { return managerId; }
    public EmploymentType getEmploymentType() { return employmentType; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getEndReason() { return endReason; }
    public UUID getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
