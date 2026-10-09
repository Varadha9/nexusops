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
class PolicyApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrpol"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
    }

    private UUID policy(String title, String body) throws Exception {
        return Api.id(owner.post("/api/v1/hr/policies", "{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT")));
    }

    @Test
    void policiesAreWrittenPublishedUnpublishedAndArchived() throws Exception {
        UUID id = policy("Leave policy", "Annual leave is 20 days a year.");
        owner.post("/api/v1/hr/policies/" + id + "/publish", "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.publishedAt").exists());
        owner.put("/api/v1/hr/policies/" + id, "{\"title\":\"Leave policy\",\"body\":\"Annual leave is 22 days.\","
                + "\"version\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        owner.post("/api/v1/hr/policies/" + id + "/unpublish", "{\"version\":2}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.publishedAt").doesNotExist());
        owner.post("/api/v1/hr/policies/" + id + "/archive", "{\"version\":3}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        owner.post("/api/v1/hr/policies/" + id + "/publish", "{\"version\":4}").andExpect(status().isConflict());
        owner.put("/api/v1/hr/policies/" + id, "{\"title\":\"X\",\"body\":\"Y\",\"version\":4}")
                .andExpect(status().isConflict());
        owner.post("/api/v1/hr/policies", "{\"title\":\"\",\"body\":\"x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("title"));
        owner.post("/api/v1/hr/policies", "{\"title\":\"T\",\"body\":\"\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("body"));
    }

    @Test
    void searchFindsWordsInTitlesAndBodiesAndToleratesOddInput() throws Exception {
        UUID leave = policy("Leave policy", "Annual leave is 20 days a year.");
        UUID travel = policy("Travel policy", "Book economy class; claim expenses within 30 days.");
        owner.post("/api/v1/hr/policies/" + leave + "/publish", "{\"version\":0}").andExpect(status().isOk());
        owner.post("/api/v1/hr/policies/" + travel + "/publish", "{\"version\":0}").andExpect(status().isOk());
        owner.get("/api/v1/hr/policies?q=expenses").andExpect(jsonPath("$.items[*].title").value(
                Matchers.contains("Travel policy")));
        owner.get("/api/v1/hr/policies?q=leave").andExpect(jsonPath("$.items[0].title").value("Leave policy"));
        owner.get("/api/v1/hr/policies?q=" + java.net.URLEncoder.encode("\"leave' & | !:*", java.nio.charset.StandardCharsets.UTF_8))
                .andExpect(status().isOk());
    }

    @Test
    void staffReadPublishedPoliciesOnly() throws Exception {
        UUID draft = policy("Draft policy", "Not yet.");
        UUID published = policy("Code of conduct", "Be kind.");
        owner.post("/api/v1/hr/policies/" + published + "/publish", "{\"version\":0}").andExpect(status().isOk());
        UUID role = TestRoles.create(mvc, owner.session(), "Staff", "hr.self.service");
        Api staff = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        staff.get("/api/v1/hr/policies").andExpect(jsonPath("$.items[*].title").value(Matchers.contains("Code of conduct")));
        staff.get("/api/v1/hr/policies?status=DRAFT").andExpect(jsonPath("$.total").value(0));
        staff.get("/api/v1/hr/policies/" + published).andExpect(status().isOk());
        staff.get("/api/v1/hr/policies/" + draft).andExpect(status().isNotFound());
        staff.post("/api/v1/hr/policies", "{\"title\":\"X\",\"body\":\"Y\"}").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/policies").andExpect(jsonPath("$.total").value(2));
    }
}
