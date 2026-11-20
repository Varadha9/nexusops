package com.nexusops.hrms;

public record LeaveTypeCommand(String name, Boolean paid, Integer annualAllowance) {}
