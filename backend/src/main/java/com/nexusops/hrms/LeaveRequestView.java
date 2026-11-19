package com.nexusops.hrms;

import com.nexusops.collaboration.MemberRef;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record LeaveRequestView(UUID id, EmployeeRef employee, LeaveTypeRef type, LocalDate startDate,
        LocalDate endDate, int days, String reason, LeaveStatus status, MemberRef requestedBy, Instant requestedAt,
        MemberRef decidedBy, Instant decidedAt, String decisionNote, Instant cancelledAt, long version) {}
