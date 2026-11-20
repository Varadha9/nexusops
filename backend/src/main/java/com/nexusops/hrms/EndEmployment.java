package com.nexusops.hrms;

import java.time.LocalDate;

public record EndEmployment(LocalDate endDate, String reason) {}
