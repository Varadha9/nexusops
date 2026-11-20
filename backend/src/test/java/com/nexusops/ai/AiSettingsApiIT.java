package com.nexusops.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.FakeAiClient;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHelpDesk;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class AiSettingsApiIT extends IntegrationTestSupport {

    static final String SETTINGS = "/api/v1/ai/settings";
    static final String LIMIT_RULE = "Enter a limit from 1 to 100000.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;
    @Autowired FakeAiClient ai;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ai.reset();
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("aiset"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
        TestHr.enable(owner);
    }

    @AfterEach
    void resetFake() {
        ai.reset();
    }

    private List<Map<String, Object>> audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForList("select entity_type, entity_id, before::text as before, "
                + "after::text as after, metadata::text as metadata from audit_events where action = ?", action);
    }

    @Test
    void settingsStartEnabledWithA500LimitAndAreUpdatedWithAuditing() throws Exception {
        owner.get(SETTINGS).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.monthlyLimit").value(500))
                .andExpect(jsonPath("$.usedThisMonth").value(0))
                .andExpect(jsonPath("$.provider").value("LOCAL"))
                .andExpect(jsonPath("$.indexedChunks").value(0))
                .andExpect(jsonPath("$.version").value(0));
        owner.get("/api/v1/ai/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true)).andExpect(jsonPath("$.provider").value("LOCAL"));

        owner.put(SETTINGS, "{\"enabled\":false,\"monthlyLimit\":100,\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false))
                .andExpect(jsonPath("$.monthlyLimit").value(100))
                .andExpect(jsonPath("$.version").value(1));
        owner.get(SETTINGS).andExpect(jsonPath("$.enabled").value(false)).andExpect(jsonPath("$.monthlyLimit").value(100));
        assertThat(audits("AiSettingsUpdated")).singleElement().satisfies(a -> {
            assertThat(a).containsEntry("entity_type", "ai_settings").containsEntry("entity_id", ws.tenantId().toString());
            assertThat((String) a.get("before")).contains("\"enabled\": true", "\"monthlyLimit\": 500");
            assertThat((String) a.get("after")).contains("\"enabled\": false", "\"monthlyLimit\": 100");
        });

        // a stale version is a conflict and changes nothing
        owner.put(SETTINGS, "{\"enabled\":true,\"monthlyLimit\":200,\"version\":0}").andExpect(status().isConflict());
        owner.get(SETTINGS).andExpect(jsonPath("$.monthlyLimit").value(100)).andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void theLimitMustBeFrom1To100000() throws Exception {
        for (String limit : List.of("0", "100001", "null")) {
            owner.put(SETTINGS, "{\"enabled\":true,\"monthlyLimit\":" + limit + ",\"version\":0}")
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("monthlyLimit"))
                    .andExpect(jsonPath("$.errors[0].message").value(LIMIT_RULE));
        }
        owner.put(SETTINGS, "{\"enabled\":true,\"monthlyLimit\":100000,\"version\":0}").andExpect(status().isOk());
        owner.put(SETTINGS, "{\"enabled\":true,\"monthlyLimit\":1,\"version\":1}").andExpect(status().isOk());
    }

    @Test
    void usageCountsThisMonthsRequests() throws Exception {
        owner.post("/api/v1/ai/hr/policy-questions", "{\"question\":\"Can I work remotely?\"}")
                .andExpect(status().isOk());
        owner.post("/api/v1/ai/hr/policy-questions", "{\"question\":\"Can I work remotely?\"}")
                .andExpect(status().isOk());
        // a row from last month does not count
        OwnerJdbc.ownerAs(ws.tenantId()).update("insert into ai_requests (id, tenant_id, feature, mode, model, "
                + "prompt_version, latency_ms, created_at) values (?, ?, 'POLICY_QUESTION', 'LOCAL', "
                + "'bge-small-en-v1.5', 'policy-v1', 1, date_trunc('month', now() AT TIME ZONE 'UTC') "
                + "AT TIME ZONE 'UTC' - interval '1 second')", UUID.randomUUID(), ws.tenantId());
        owner.get(SETTINGS).andExpect(jsonPath("$.usedThisMonth").value(2));
    }

    @Test
    void rebuildReindexesPublishedDocumentsAndIsAudited() throws Exception {
        UUID policy = Api.id(owner.post("/api/v1/hr/policies",
                "{\"title\":\"Remote work policy\",\"body\":\"Staff may work remotely two days a week.\"}"));
        owner.post("/api/v1/hr/policies/" + policy + "/publish", "{\"version\":0}").andExpect(status().isOk());
        UUID article = Api.id(owner.post("/api/v1/helpdesk/articles",
                "{\"title\":\"Clearing a paper jam\",\"body\":\"Open the rear tray.\"}"));
        owner.post("/api/v1/helpdesk/articles/" + article + "/publish", "{\"version\":0}").andExpect(status().isOk());
        OwnerJdbc.ownerAs(ws.tenantId()).update("delete from ai_chunks");

        owner.post("/api/v1/ai/index/rebuild", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.indexedSources").value(2))
                .andExpect(jsonPath("$.indexedChunks").value(2));
        owner.get(SETTINGS).andExpect(jsonPath("$.indexedChunks").value(2));
        assertThat(audits("AiIndexRebuilt")).singleElement().satisfies(a -> {
            assertThat(a).containsEntry("entity_type", "ai_index");
            assertThat((String) a.get("metadata")).contains("\"indexedSources\": 2", "\"indexedChunks\": 2");
        });

        ai.down = true;
        owner.post("/api/v1/ai/index/rebuild", "").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value("The AI service is unavailable. Try again shortly."));
        owner.get(SETTINGS).andExpect(status().isOk()).andExpect(jsonPath("$.provider").value("UNAVAILABLE"))
                .andExpect(jsonPath("$.indexedChunks").value(2));
        assertThat(audits("AiIndexRebuilt")).hasSize(1);
    }

    @Test
    void onlySettingsManagersSeeOrChangeSettings() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "AI user", "ai.assistant.use");
        Api member = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        member.get("/api/v1/ai/status").andExpect(status().isOk());
        member.get(SETTINGS).andExpect(status().isForbidden());
        member.put(SETTINGS, "{\"enabled\":false,\"monthlyLimit\":1,\"version\":0}").andExpect(status().isForbidden());
        member.post("/api/v1/ai/index/rebuild", "").andExpect(status().isForbidden());
        owner.get(SETTINGS).andExpect(jsonPath("$.enabled").value(true));
    }
}
