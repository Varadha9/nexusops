package com.nexusops.hrms;

import java.util.UUID;

interface OnboardingReader {
    OnboardingProgress progress(UUID employeeId);
}
