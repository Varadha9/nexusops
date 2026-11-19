package com.nexusops.hrms;

import java.util.List;

/** D17: what a signed-in employee sees about themselves. */
public record MeView(EmployeeView employee, int year, List<LeaveBalanceView> balances, List<LeaveRequestView> requests,
        List<TrainingAssignmentView> trainings) {}
