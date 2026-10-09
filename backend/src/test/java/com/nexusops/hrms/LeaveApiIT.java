package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Dates in 2027: 1 March is a Monday. */
@AutoConfigureMockMvc
class LeaveApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID annual;
    UUID unpaid;
    UUID meera;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrleave"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
        annual = TestHr.leaveType(owner, "Annual leave");
        unpaid = TestHr.leaveType(owner, "Unpaid leave");
        meera = TestHr.hire(owner, "Meera", "Iyer", "E-1");
    }

    private org.springframework.test.web.servlet.ResultActions ask(UUID employee, UUID type, String start, String end)
            throws Exception {
        return owner.post("/api/v1/hr/leave-requests", "{\"employeeId\":\"" + employee + "\",\"typeId\":\"" + type
                + "\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}");
    }

    private UUID userId(String email) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users where email = ?", UUID.class, email);
    }

    @Test
    void aRequestCountsWorkingDaysAndIsApproved() throws Exception {
        owner.post("/api/v1/hr/holidays", "{\"date\":\"2027-03-03\",\"name\":\"Holi\"}").andExpect(status().isCreated());
        UUID id = Api.id(ask(meera, annual, "2027-03-01", "2027-03-05").andExpect(status().isCreated())
                .andExpect(jsonPath("$.days").value(4))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.employee.name").value("Meera Iyer")));
        owner.get("/api/v1/hr/employees/" + meera + "/leave-balances?year=2027")
                .andExpect(jsonPath("$[0].type.name").value("Annual leave"))
                .andExpect(jsonPath("$[0].entitlement").value(20))
                .andExpect(jsonPath("$[0].pending").value(4))
                .andExpect(jsonPath("$[0].available").value(16));
        owner.post("/api/v1/hr/leave-requests/" + id + "/approve", "{\"note\":\"Enjoy\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decisionNote").value("Enjoy"))
                .andExpect(jsonPath("$.decidedBy.id").value(userId(ws.email()).toString()));
        owner.get("/api/v1/hr/employees/" + meera + "/leave-balances?year=2027")
                .andExpect(jsonPath("$[0].taken").value(4)).andExpect(jsonPath("$[0].pending").value(0));
        owner.post("/api/v1/hr/leave-requests/" + id + "/approve", "{\"version\":1}").andExpect(status().isConflict());
        owner.get("/api/v1/hr/leave/away?from=2027-03-02&to=2027-03-02")
                .andExpect(jsonPath("$[*].employee.name").value(Matchers.contains("Meera Iyer")));
        owner.get("/api/v1/hr/leave/away?from=2027-03-08&to=2027-03-12").andExpect(jsonPath("$").isEmpty());
        owner.get("/api/v1/hr/leave-requests?status=APPROVED").andExpect(jsonPath("$.total").value(1));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action in ('LeaveRequested', 'LeaveApproved')", Long.class))
                .isEqualTo(2);
    }

    @Test
    void fridayToMondayIsTwoDaysAndAWeekendOnlyRequestIsRefused() throws Exception {
        ask(meera, annual, "2027-03-05", "2027-03-08").andExpect(status().isCreated())
                .andExpect(jsonPath("$.days").value(2));
        ask(meera, annual, "2027-03-13", "2027-03-14").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Choose at least one working day."));
    }

    @Test
    void invalidRequestsAreFieldErrors() throws Exception {
        String[][] cases = {
                {"{\"employeeId\":\"" + UUID.randomUUID() + "\",\"typeId\":\"" + annual
                        + "\",\"startDate\":\"2027-03-01\",\"endDate\":\"2027-03-01\"}", "employeeId"},
                {"{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + UUID.randomUUID()
                        + "\",\"startDate\":\"2027-03-01\",\"endDate\":\"2027-03-01\"}", "typeId"},
                {"{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual + "\",\"endDate\":\"2027-03-01\"}", "startDate"},
                {"{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual
                        + "\",\"startDate\":\"2027-03-05\",\"endDate\":\"2027-03-01\"}", "endDate"},
                {"{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual
                        + "\",\"startDate\":\"2027-12-30\",\"endDate\":\"2028-01-03\"}", "endDate"},
                {"{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual
                        + "\",\"startDate\":\"2025-03-03\",\"endDate\":\"2025-03-03\"}", "startDate"},
                {"{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual + "\",\"startDate\":\"2027-03-01\","
                        + "\"endDate\":\"2027-03-01\",\"reason\":\"" + "x".repeat(501) + "\"}", "reason"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/hr/leave-requests", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/hr/leave-requests", "{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual
                + "\",\"startDate\":\"2027-12-30\",\"endDate\":\"2028-01-03\"}")
                .andExpect(jsonPath("$.errors[0].message").value("Split a request at the end of the year."));
    }

    @Test
    void overlappingRequestsAreRefusedButRejectedOrCancelledOnesDontCount() throws Exception {
        UUID first = Api.id(ask(meera, annual, "2027-03-01", "2027-03-05").andExpect(status().isCreated()));
        ask(meera, unpaid, "2027-03-05", "2027-03-08").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This overlaps another leave request."));
        owner.post("/api/v1/hr/leave-requests/" + first + "/reject", "{\"note\":\"Busy week\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        ask(meera, unpaid, "2027-03-05", "2027-03-08").andExpect(status().isCreated());
    }

    @Test
    void balanceCanBeUsedExactlyAndFractionsPrint() throws Exception {
        owner.post("/api/v1/hr/employees/" + meera + "/leave-adjustments", "{\"typeId\":\"" + annual
                + "\",\"year\":2027,\"days\":-17,\"reason\":\"Joined mid-year\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entitlement").value(3));
        ask(meera, annual, "2027-03-01", "2027-03-03").andExpect(status().isCreated()); // exactly 3
        ask(meera, annual, "2027-03-08", "2027-03-08").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Not enough leave left: 0 days available."));
        owner.post("/api/v1/hr/employees/" + meera + "/leave-adjustments", "{\"typeId\":\"" + annual
                + "\",\"year\":2027,\"days\":0.5,\"reason\":\"Goodwill\"}").andExpect(status().isOk());
        ask(meera, annual, "2027-03-08", "2027-03-08").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Not enough leave left: 0.5 days available."));
        // unlimited types are never short
        ask(meera, unpaid, "2027-03-08", "2027-03-12").andExpect(status().isCreated());
        String[][] bad = {
                {"{\"typeId\":\"" + annual + "\",\"year\":2027,\"days\":0,\"reason\":\"x\"}", "days"},
                {"{\"typeId\":\"" + annual + "\",\"year\":2027,\"days\":0.3,\"reason\":\"x\"}", "days"},
                {"{\"typeId\":\"" + annual + "\",\"year\":2027,\"days\":367,\"reason\":\"x\"}", "days"},
                {"{\"typeId\":\"" + annual + "\",\"year\":2027,\"days\":1}", "reason"},
                {"{\"typeId\":\"" + annual + "\",\"days\":1,\"reason\":\"x\"}", "year"},
        };
        for (String[] c : bad) {
            owner.post("/api/v1/hr/employees/" + meera + "/leave-adjustments", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
    }

    @Test
    void endingEmploymentCancelsOnlyLaterLeave() throws Exception {
        UUID march = Api.id(ask(meera, annual, "2027-03-01", "2027-03-05"));
        owner.post("/api/v1/hr/leave-requests/" + march + "/approve", "{\"version\":0}").andExpect(status().isOk());
        UUID june = Api.id(ask(meera, annual, "2027-06-07", "2027-06-11"));
        long version = ((Number) Api.read(owner.get("/api/v1/hr/employees/" + meera), "$.version")).longValue();
        owner.post("/api/v1/hr/employees/" + meera + "/end", "{\"endDate\":\"2027-04-30\",\"version\":" + version + "}")
                .andExpect(status().isOk());
        owner.get("/api/v1/hr/leave-requests?employeeId=" + meera)
                .andExpect(jsonPath("$.items[?(@.id == '" + march + "')].status").value(Matchers.contains("APPROVED")))
                .andExpect(jsonPath("$.items[?(@.id == '" + june + "')].status").value(Matchers.contains("CANCELLED")));
        owner.get("/api/v1/hr/employees/" + meera + "/leave-balances?year=2027")
                .andExpect(jsonPath("$[0].taken").value(5)).andExpect(jsonPath("$[0].pending").value(0));
        // and no new leave after the last day
        ask(meera, annual, "2027-05-03", "2027-05-03").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("endDate"));
    }

    @Test
    void theManagerHearsOfRequestsAndTheEmployeeOfDecisions() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Staff", "hr.self.service");
        Workspace managerLogin = members.create(ws.tenantId(), Set.of(role));
        Workspace staffLogin = members.create(ws.tenantId(), Set.of(role));
        UUID manager = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Ravi\",\"lastName\":"
                + "\"Kumar\"},\"employeeNumber\":\"M-1\",\"startDate\":\"2026-01-05\",\"userId\":\""
                + userId(managerLogin.email()) + "\"}"));
        UUID asha = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Asha\",\"lastName\":"
                + "\"Rao\"},\"employeeNumber\":\"E-2\",\"startDate\":\"2026-01-05\",\"managerId\":\"" + manager
                + "\",\"userId\":\"" + userId(staffLogin.email()) + "\"}"));
        UUID id = Api.id(ask(asha, annual, "2027-03-01", "2027-03-02"));
        assertThat(mail.sentTo(managerLogin.email())).anySatisfy(m -> assertThat(m.subject())
                .isEqualTo("Leave request: Asha Rao, 2027-03-01 to 2027-03-02"));
        owner.post("/api/v1/hr/leave-requests/" + id + "/approve", "{\"version\":0}").andExpect(status().isOk());
        assertThat(mail.sentTo(staffLogin.email())).anySatisfy(m -> assertThat(m.subject())
                .isEqualTo("Your leave request was approved"));
    }

    @Test
    void someoneElseMustDecideYourOwnRequest() throws Exception {
        UUID self = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Ada\",\"lastName\":\"Owner\"},"
                + "\"employeeNumber\":\"O-1\",\"startDate\":\"2026-01-05\",\"userId\":\"" + userId(ws.email()) + "\"}"));
        UUID id = Api.id(ask(self, annual, "2027-03-01", "2027-03-01"));
        owner.post("/api/v1/hr/leave-requests/" + id + "/approve", "{\"version\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Someone else must decide your own request."));
        owner.post("/api/v1/hr/leave-requests/" + id + "/reject", "{\"version\":0}").andExpect(status().isConflict());
        owner.post("/api/v1/hr/leave-requests/" + id + "/cancel", "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void approversCantCancelLeaveThatIsOver() throws Exception {
        UUID id = Api.id(ask(meera, annual, "2027-03-01", "2027-03-05"));
        owner.post("/api/v1/hr/leave-requests/" + id + "/approve", "{\"version\":0}").andExpect(status().isOk());
        OwnerJdbc.ownerAs(ws.tenantId()).update("update leave_requests set start_date = date '2026-03-02', "
                + "end_date = date '2026-03-06' where id = ?", id);
        owner.post("/api/v1/hr/leave-requests/" + id + "/cancel", "{\"version\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This request can no longer be cancelled."));
    }

    @Test
    void onlyApproversSeeAndDecideRequests() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "HR viewer", "hr.employee.read");
        Api viewer = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        viewer.get("/api/v1/hr/leave-requests").andExpect(status().isForbidden());
        viewer.get("/api/v1/hr/leave/away?from=2027-03-01&to=2027-03-05").andExpect(status().isForbidden());
        viewer.get("/api/v1/hr/employees/" + meera + "/leave-balances?year=2027").andExpect(status().isOk());
        viewer.post("/api/v1/hr/employees/" + meera + "/leave-adjustments", "{}").andExpect(status().isForbidden());
    }
}
