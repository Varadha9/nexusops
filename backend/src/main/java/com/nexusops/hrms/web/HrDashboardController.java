package com.nexusops.hrms.web;

import com.nexusops.hrms.HrDashboardService;
import com.nexusops.hrms.HrDashboardView;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/dashboard")
class HrDashboardController {

    private final HrDashboardService dashboard;

    HrDashboardController(HrDashboardService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('hr.employee.read')")
    HrDashboardView dashboard() {
        return dashboard.dashboard();
    }
}
