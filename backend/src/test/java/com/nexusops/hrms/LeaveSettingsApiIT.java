package com.nexusops.hrms;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
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

@AutoConfigureMockMvc
class LeaveSettingsApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrlvset"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
    }

    @Test
    void everyWorkspaceStartsWithThreeLeaveTypes() throws Exception {
        owner.get("/api/v1/hr/leave-types")
                .andExpect(jsonPath("$[*].name").value(Matchers.contains("Annual leave", "Sick leave", "Unpaid leave")))
                .andExpect(jsonPath("$[0].annualAllowance").value(20))
                .andExpect(jsonPath("$[1].annualAllowance").value(10))
                .andExpect(jsonPath("$[2].annualAllowance").doesNotExist())
                .andExpect(jsonPath("$[2].paid").value(false));
    }

    @Test
    void leaveTypesAreCreatedEditedAndArchived() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/leave-types", "{\"name\":\"Study leave\",\"paid\":true,"
                + "\"annualAllowance\":5}").andExpect(status().isCreated()));
        owner.put("/api/v1/hr/leave-types/" + id, "{\"name\":\"Study leave\",\"paid\":false,\"annualAllowance\":null,"
                + "\"version\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.annualAllowance").doesNotExist());
        owner.post("/api/v1/hr/leave-types", "{\"name\":\"study LEAVE\",\"paid\":true}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.post("/api/v1/hr/leave-types", "{\"name\":\"X\",\"paid\":true,\"annualAllowance\":367}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("annualAllowance"));
        owner.post("/api/v1/hr/leave-types", "{\"name\":\"X\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("paid"));
        owner.post("/api/v1/hr/leave-types/" + id + "/archive", "").andExpect(status().isOk());
        owner.get("/api/v1/hr/leave-types").andExpect(jsonPath("$[*].name", Matchers.not(Matchers.hasItem("Study leave"))));
    }

    @Test
    void holidaysAreAddedAndRemovedPerYear() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/holidays", "{\"date\":\"2027-01-26\",\"name\":\"Republic Day\"}")
                .andExpect(status().isCreated()));
        owner.post("/api/v1/hr/holidays", "{\"date\":\"2027-08-15\",\"name\":\"Independence Day\"}")
                .andExpect(status().isCreated());
        owner.post("/api/v1/hr/holidays", "{\"date\":\"2028-01-26\",\"name\":\"Republic Day\"}")
                .andExpect(status().isCreated());
        owner.post("/api/v1/hr/holidays", "{\"date\":\"2027-01-26\",\"name\":\"Again\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("date"));
        owner.post("/api/v1/hr/holidays", "{\"name\":\"No date\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("date"));
        owner.get("/api/v1/hr/holidays?year=2027").andExpect(jsonPath("$[*].name").value(
                Matchers.contains("Republic Day", "Independence Day")));
        owner.delete("/api/v1/hr/holidays/" + id).andExpect(status().isNoContent());
        owner.get("/api/v1/hr/holidays?year=2027").andExpect(jsonPath("$.length()").value(1));
        owner.delete("/api/v1/hr/holidays/" + id).andExpect(status().isNotFound());
    }

    @Test
    void selfServiceUsersSeeLeaveTypesAndHolidaysButCantChangeThem() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Staff", "hr.self.service");
        Api staff = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        staff.get("/api/v1/hr/leave-types").andExpect(status().isOk());
        staff.get("/api/v1/hr/holidays?year=2027").andExpect(status().isOk());
        staff.post("/api/v1/hr/holidays", "{\"date\":\"2027-01-26\",\"name\":\"X\"}").andExpect(status().isForbidden());
        staff.post("/api/v1/hr/leave-types", "{\"name\":\"X\",\"paid\":true}").andExpect(status().isForbidden());
    }
}
