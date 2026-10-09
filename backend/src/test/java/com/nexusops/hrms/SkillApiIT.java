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

@AutoConfigureMockMvc
class SkillApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrskill"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select count(*) from audit_events where action = ?",
                Long.class, action);
    }

    @Test
    void createsRenamesArchivesAndRestores() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/skills", "{\"name\":\"  Customer   service \"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Customer service")));
        TestHr.skill(owner, "Accounting");
        owner.post("/api/v1/hr/skills", "{\"name\":\"CUSTOMER SERVICE\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.put("/api/v1/hr/skills/" + id, "{\"name\":\"Customer care\",\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        owner.put("/api/v1/hr/skills/" + id, "{\"name\":\"Customer care\",\"version\":0}")
                .andExpect(status().isConflict());
        owner.get("/api/v1/hr/skills").andExpect(jsonPath("$[*].name").value(Matchers.contains("Accounting",
                "Customer care")));
        owner.post("/api/v1/hr/skills/" + id + "/archive", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").exists());
        owner.get("/api/v1/hr/skills").andExpect(jsonPath("$[*].name").value(Matchers.contains("Accounting")));
        owner.get("/api/v1/hr/skills?archived=true").andExpect(jsonPath("$[*].name").value(
                Matchers.contains("Customer care")));
        owner.post("/api/v1/hr/skills/" + id + "/restore", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
        assertThat(audits("SkillCreated")).isEqualTo(2);
        assertThat(audits("SkillUpdated")).isEqualTo(1);
        assertThat(audits("SkillArchived")).isEqualTo(1);
        assertThat(audits("SkillRestored")).isEqualTo(1);
    }

    @Test
    void invalidSkillsAreFieldErrors() throws Exception {
        owner.post("/api/v1/hr/skills", "{\"name\":\"\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.post("/api/v1/hr/skills", "{\"name\":\"" + "x".repeat(61) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.put("/api/v1/hr/skills/" + UUID.randomUUID(), "{\"name\":\"X\",\"version\":0}")
                .andExpect(status().isNotFound());
        UUID id = TestHr.skill(owner, "Welding");
        owner.put("/api/v1/hr/skills/" + id, "{\"name\":\"Welding\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
    }

    @Test
    void readersSeeSkillsButOnlySettingsManagersChangeThem() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "HR viewer", "hr.employee.read");
        Api viewer = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        viewer.get("/api/v1/hr/skills").andExpect(status().isOk());
        viewer.post("/api/v1/hr/skills", "{\"name\":\"X\"}").andExpect(status().isForbidden());
    }
}
