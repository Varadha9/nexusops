package com.nexusops.hrms;

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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class SelfServiceApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    Api staff;
    UUID meera;
    UUID other;
    UUID annual;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrme"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
        annual = TestHr.leaveType(owner, "Annual leave");
        UUID role = TestRoles.create(mvc, owner.session(), "Staff", "hr.self.service");
        Workspace login = members.create(ws.tenantId(), Set.of(role));
        UUID userId = OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users where email = ?",
                UUID.class, login.email());
        meera = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Meera\",\"lastName\":\"Iyer\"},"
                + "\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\",\"userId\":\"" + userId + "\"}"));
        other = TestHr.hire(owner, "Arun", "Shah", "E-2");
        staff = Api.login(mvc, login);
    }

    @Test
    void anEmployeeSeesTheirOwnRecordBalancesAndRequestsLeave() throws Exception {
        staff.get("/api/v1/hr/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.employee.id").value(meera.toString()))
                .andExpect(jsonPath("$.balances[0].type.name").value("Annual leave"));
        UUID id = Api.id(staff.post("/api/v1/hr/me/leave-requests", "{\"typeId\":\"" + annual
                + "\",\"startDate\":\"2027-03-01\",\"endDate\":\"2027-03-02\",\"reason\":\"Family\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.employee.id").value(meera.toString()))
                .andExpect(jsonPath("$.days").value(2)));
        staff.get("/api/v1/hr/me").andExpect(jsonPath("$.requests[0].id").value(id.toString()));
        staff.post("/api/v1/hr/me/leave-requests/" + id + "/cancel", "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void anEmployeeCompletesTheirOwnTraining() throws Exception {
        UUID skill = TestHr.skill(owner, "Excel");
        UUID course = Api.id(owner.post("/api/v1/hr/courses", "{\"title\":\"Excel basics\",\"skills\":[{\"skillId\":\""
                + skill + "\",\"level\":2}]}"));
        UUID assignment = Api.id(owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + course
                + "\",\"employeeId\":\"" + meera + "\"}"));
        staff.get("/api/v1/hr/me").andExpect(jsonPath("$.trainings[0].course.title").value("Excel basics"));
        staff.post("/api/v1/hr/me/training/" + assignment + "/complete", "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        owner.get("/api/v1/hr/employees/" + meera).andExpect(jsonPath("$.skills[0].level").value(2));
    }

    @Test
    void selfServiceNeverReachesSomeoneElse() throws Exception {
        UUID theirs = Api.id(owner.post("/api/v1/hr/leave-requests", "{\"employeeId\":\"" + other + "\",\"typeId\":\""
                + annual + "\",\"startDate\":\"2027-03-01\",\"endDate\":\"2027-03-01\"}"));
        staff.post("/api/v1/hr/me/leave-requests/" + theirs + "/cancel", "{\"version\":0}").andExpect(status().isNotFound());
        UUID skill = TestHr.skill(owner, "Excel");
        UUID course = Api.id(owner.post("/api/v1/hr/courses", "{\"title\":\"Excel basics\",\"skills\":[{\"skillId\":\""
                + skill + "\",\"level\":2}]}"));
        UUID assignment = Api.id(owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + course
                + "\",\"employeeId\":\"" + other + "\"}"));
        staff.post("/api/v1/hr/me/training/" + assignment + "/complete", "{\"version\":0}").andExpect(status().isNotFound());
        staff.get("/api/v1/hr/employees/" + other).andExpect(status().isForbidden());
        staff.get("/api/v1/hr/leave-requests").andExpect(status().isForbidden());
        // the leave body can't name another employee either: the route ignores any employeeId
        staff.post("/api/v1/hr/me/leave-requests", "{\"employeeId\":\"" + other + "\",\"typeId\":\"" + annual
                + "\",\"startDate\":\"2027-04-05\",\"endDate\":\"2027-04-05\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.employee.id").value(meera.toString()));
    }

    @Test
    void anUnlinkedAccountIsToldSo() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Visitor", "hr.self.service");
        Api visitor = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        visitor.get("/api/v1/hr/me").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Your account isn't linked to an employee record."));
        visitor.post("/api/v1/hr/me/leave-requests", "{\"typeId\":\"" + annual + "\",\"startDate\":\"2027-03-01\","
                + "\"endDate\":\"2027-03-01\"}").andExpect(status().isNotFound());
    }

    @Test
    void anEmployeeCantCancelLeaveThatHasStarted() throws Exception {
        UUID id = Api.id(staff.post("/api/v1/hr/me/leave-requests", "{\"typeId\":\"" + annual
                + "\",\"startDate\":\"2027-03-01\",\"endDate\":\"2027-03-02\"}"));
        owner.post("/api/v1/hr/leave-requests/" + id + "/approve", "{\"version\":0}").andExpect(status().isOk());
        OwnerJdbc.ownerAs(ws.tenantId()).update("update leave_requests set start_date = date '2026-03-02', "
                + "end_date = date '2026-03-03' where id = ?", id);
        staff.post("/api/v1/hr/me/leave-requests/" + id + "/cancel", "{\"version\":1}").andExpect(status().isConflict());
    }
}
