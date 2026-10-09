package com.nexusops.hrms;

import java.time.LocalDate;

public record AwayView(EmployeeRef employee, LeaveTypeRef type, LocalDate startDate, LocalDate endDate) {}
