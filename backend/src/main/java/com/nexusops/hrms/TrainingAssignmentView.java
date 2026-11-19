package com.nexusops.hrms;

import com.nexusops.collaboration.MemberRef;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record TrainingAssignmentView(UUID id, CourseRef course, EmployeeRef employee, LocalDate dueOn,
        TrainingStatus status, boolean overdue, MemberRef assignedBy, Instant assignedAt, LocalDate completedOn,
        String completionNote, long version) {}
