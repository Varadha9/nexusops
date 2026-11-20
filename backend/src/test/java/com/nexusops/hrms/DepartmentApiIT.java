package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class DepartmentApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrdept"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
    }

    @Test
    void createsRenamesArchivesAndRestores() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/departments", "{\"name\":\"Sales\",\"description\":\"Field sales\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.description").value("Field sales")));
        owner.post("/api/v1/hr/departments", "{\"name\":\"sales\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.put("/api/v1/hr/departments/" + id, "{\"name\":\"Sales & Marketing\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").doesNotExist());
        owner.post("/api/v1/hr/departments/" + id + "/archive", "").andExpect(status().isOk());
        owner.get("/api/v1/hr/departments?archived=true").andExpect(jsonPath("$[*].name").value(
                Matchers.contains("Sales & Marketing")));
        owner.put("/api/v1/hr/departments/" + id, "{\"name\":\"Sales\",\"version\":2}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This record is archived."));
        owner.post("/api/v1/hr/departments/" + id + "/restore", "").andExpect(status().isOk());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action like 'Department%'", Long.class)).isEqualTo(4);
    }

    @Test
    void invalidDepartmentsAreFieldErrors() throws Exception {
        owner.post("/api/v1/hr/departments", "{\"name\":\"" + "x".repeat(81) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.post("/api/v1/hr/departments", "{\"name\":\"Ok\",\"description\":\"" + "x".repeat(501) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("description"));
    }
}
