package com.nexusops.hrms;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.temporal.TemporalAdjusters;
import java.util.List;
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
        UUID dev = TestHr.hire(owner, "Dev", "Patel", "O-1"); // started 2026-01-05, no department
        Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Zoe\",\"lastName\":\"New\"},"
                + "\"employeeNumber\":\"N-1\",\"startDate\":\"" + today.plusDays(14) + "\"}"));
        UUID gone = TestHr.hire(owner, "Old", "Timer", "X-1");
        // Old Timer's onboarding is still open when employment ends: it must not count as in progress
        UUID standard = UUID.fromString(Api.<List<String>>read(owner.get("/api/v1/hr/onboarding-templates"),
                "$[?(@.name == 'Standard onboarding')].id").getFirst());
        owner.post("/api/v1/hr/employees/" + gone + "/onboarding", "{\"templateId\":\"" + standard + "\"}")
                .andExpect(status().isOk());
        owner.post("/api/v1/hr/employees/" + gone + "/end", "{\"endDate\":\"" + today.minusDays(5) + "\",\"version\":0}")
                .andExpect(status().isOk());
        // Arun is away today (approved leave written directly: today may be a weekend)
        UUID annual = TestHr.leaveType(owner, "Annual leave");
        OwnerJdbc.ownerAs(ws.tenantId()).update("insert into leave_requests (id, tenant_id, employee_id, type_id, "
                + "start_date, end_date, days, status, requested_at, decided_at, updated_at) values (?, ?, ?, ?, ?, ?, 1, "
                + "'APPROVED', now(), now(), now())", UUID.randomUUID(), ws.tenantId(), arun, annual, today, today);
        // Dev is away in two days (written directly, as above)
        OwnerJdbc.ownerAs(ws.tenantId()).update("insert into leave_requests (id, tenant_id, employee_id, type_id, "
                + "start_date, end_date, days, status, requested_at, decided_at, updated_at) values (?, ?, ?, ?, ?, ?, 1, "
                + "'APPROVED', now(), now(), now())", UUID.randomUUID(), ws.tenantId(), dev, annual, today.plusDays(2),
                today.plusDays(2));
        // Meera's onboarding is in progress; Arun's training is overdue
        owner.post("/api/v1/hr/employees/" + meera + "/onboarding", "{\"templateId\":\"" + standard + "\"}")
                .andExpect(status().isOk());
        UUID course = UUID.randomUUID();
        OwnerJdbc.ownerAs(ws.tenantId()).update("insert into courses (id, tenant_id, title, created_at, updated_at) "
                + "values (?, ?, 'Overdue course', now(), now())", course, ws.tenantId());
        OwnerJdbc.ownerAs(ws.tenantId()).update("insert into training_assignments (id, tenant_id, course_id, "
                + "employee_id, status, assigned_at, due_on, updated_at) values (?, ?, ?, ?, 'ASSIGNED', now(), ?, now())",
                UUID.randomUUID(), ws.tenantId(), course, arun, today.minusDays(3));
        LocalDate nextYearMonday = LocalDate.of(today.getYear() + 1, 3, 1)
                .with(TemporalAdjusters.firstInMonth(DayOfWeek.MONDAY));
        owner.post("/api/v1/hr/leave-requests", "{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual
                + "\",\"startDate\":\"" + nextYearMonday + "\",\"endDate\":\""
                + nextYearMonday + "\"}").andExpect(status().isCreated());
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
                .andExpect(jsonPath("$.awayNext7Days[*].employee.name").value(hasItem("Dev Patel")))
                .andExpect(jsonPath("$.awayNext7Days[*].employee.name").value(not(hasItem("Arun Shah"))))
                .andExpect(jsonPath("$.overdueTrainings").value(1))
                .andExpect(jsonPath("$.onboardingInProgress").value(1))
                .andExpect(jsonPath("$.topSkillGaps[0].skill.name").value("Negotiation"))
                .andExpect(jsonPath("$.topSkillGaps[0].employees").value(2));
    }
}
