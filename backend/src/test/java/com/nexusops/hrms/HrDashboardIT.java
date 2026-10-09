package com.nexusops.hrms;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class HrDashboardIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrdash"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
    }

    @Test
    void anEmptyWorkspaceHasZeros() throws Exception {
        owner.get("/api/v1/hr/dashboard").andExpect(status().isOk())
                .andExpect(jsonPath("$.activeHeadcount").value(0))
                .andExpect(jsonPath("$.byDepartment").isEmpty())
                .andExpect(jsonPath("$.awayToday").isEmpty())
                .andExpect(jsonPath("$.topSkillGaps").isEmpty());
    }

    @Test
    void summarisesTheWorkforce() throws Exception {
        LocalDate today = LocalDate.now(java.time.ZoneOffset.UTC);
        UUID sales = TestHr.department(owner, "Sales");
        UUID negotiation = TestHr.skill(owner, "Negotiation");
        UUID role = TestHr.jobRole(owner, "Account executive", sales, Map.of(negotiation, 3));
        UUID meera = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Meera\",\"lastName\":\"Iyer\"},"
                + "\"employeeNumber\":\"S-1\",\"departmentId\":\"" + sales + "\",\"jobRoleId\":\"" + role
                + "\",\"startDate\":\"" + today.minusDays(10) + "\"}"));
        UUID arun = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Arun\",\"lastName\":\"Shah\"},"
                + "\"employeeNumber\":\"S-2\",\"departmentId\":\"" + sales + "\",\"jobRoleId\":\"" + role
                + "\",\"startDate\":\"2025-01-06\"}"));
        TestHr.hire(owner, "Dev", "Patel", "O-1"); // started 2026-01-05, no department
        Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Zoe\",\"lastName\":\"New\"},"
                + "\"employeeNumber\":\"N-1\",\"startDate\":\"" + today.plusDays(14) + "\"}"));
        UUID gone = TestHr.hire(owner, "Old", "Timer", "X-1");
        owner.post("/api/v1/hr/employees/" + gone + "/end", "{\"endDate\":\"" + today.minusDays(5) + "\",\"version\":0}")
                .andExpect(status().isOk());
        // Arun is away today (approved leave written directly: today may be a weekend)
        UUID annual = TestHr.leaveType(owner, "Annual leave");
        OwnerJdbc.ownerAs(ws.tenantId()).update("insert into leave_requests (id, tenant_id, employee_id, type_id, "
                + "start_date, end_date, days, status, requested_at, decided_at, updated_at) values (?, ?, ?, ?, ?, ?, 1, "
                + "'APPROVED', now(), now(), now())", UUID.randomUUID(), ws.tenantId(), arun, annual, today, today);
        owner.post("/api/v1/hr/leave-requests", "{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual
                + "\",\"startDate\":\"" + LocalDate.of(today.getYear() + 1, 3, 1) + "\",\"endDate\":\""
                + LocalDate.of(today.getYear() + 1, 3, 1) + "\"}").andExpect(status().isCreated());
        owner.get("/api/v1/hr/dashboard")
                .andExpect(jsonPath("$.activeHeadcount").value(3))
                .andExpect(jsonPath("$.byDepartment[0].department.name").value("Sales"))
                .andExpect(jsonPath("$.byDepartment[0].employees").value(2))
                .andExpect(jsonPath("$.byDepartment[1].department").doesNotExist())
                .andExpect(jsonPath("$.byDepartment[1].employees").value(1))
                .andExpect(jsonPath("$.upcomingStarts").value(1))
                .andExpect(jsonPath("$.joinersLast90Days").value(1))
                .andExpect(jsonPath("$.leaversLast90Days").value(1))
                .andExpect(jsonPath("$.awayToday[0].employee.name").value("Arun Shah"))
                .andExpect(jsonPath("$.pendingLeaveRequests").value(1))
                .andExpect(jsonPath("$.overdueTrainings").value(0))
                .andExpect(jsonPath("$.topSkillGaps[0].skill.name").value("Negotiation"))
                .andExpect(jsonPath("$.topSkillGaps[0].employees").value(2));
    }
}
