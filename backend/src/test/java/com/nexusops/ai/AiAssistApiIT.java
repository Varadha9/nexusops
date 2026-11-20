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
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@AutoConfigureMockMvc
class AiAssistApiIT extends IntegrationTestSupport {

    static final String POLICY_QUESTIONS = "/api/v1/ai/hr/policy-questions";
    static final String NOTHING_FOUND = "I couldn't find anything about that in your workspace's documents.";
    static final String TURNED_OFF = "AI assistance is turned off for this workspace.";
    static final String LIMIT_REACHED = "This workspace has used its AI requests for this month.";
    static final String UNAVAILABLE = "The AI service is unavailable. Try again shortly.";
    static final String QUESTION_RULE = "Ask a question of 3 to 500 characters.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;
    @Autowired FakeAiClient ai;

    Workspace ws;
    Api owner;
    UUID requester;

    @BeforeEach
    void workspace() throws Exception {
        ai.reset();
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("aigw"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
        TestHr.enable(owner);
        requester = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Meera\",\"lastName\":\"Iyer\"}"));
    }

    @AfterEach
    void resetFake() {
        ai.reset();
    }

    private UUID publishedPolicy(String title, String body) throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/policies", "{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}")
                .andExpect(status().isCreated()));
        owner.post("/api/v1/hr/policies/" + id + "/publish", "{\"version\":0}").andExpect(status().isOk());
        return id;
    }

    private UUID publishedArticle(String title, String body) throws Exception {
        UUID id = Api.id(owner.post("/api/v1/helpdesk/articles", "{\"title\":\"" + title + "\",\"body\":\"" + body
                + "\"}").andExpect(status().isCreated()));
        owner.post("/api/v1/helpdesk/articles/" + id + "/publish", "{\"version\":0}").andExpect(status().isOk());
        return id;
    }

    private UUID ticket(Api as, UUID requesterId, String subject, String description) throws Exception {
        return Api.id(as.post("/api/v1/helpdesk/tickets", "{\"subject\":\"" + subject + "\",\"description\":\""
                + description + "\",\"requesterId\":\"" + requesterId + "\"}").andExpect(status().isCreated()));
    }

    private static String question(String text) {
        return "{\"question\":\"" + text + "\"}";
    }

    private List<Map<String, Object>> requests() {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForList("select id, user_id, feature, mode, model, "
                + "prompt_version, input_tokens, output_tokens, latency_ms, array_to_string(source_ids, ',') as sources "
                + "from ai_requests order by created_at");
    }

    private UUID ownerId() {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users where email = ?", UUID.class,
                ws.email());
    }

    @Test
    void aPolicyQuestionIsAnsweredFromPublishedPoliciesWithTheirCitations() throws Exception {
        UUID remote = publishedPolicy("Remote work policy", "Staff may work remotely two days a week.");
        owner.post(POLICY_QUESTIONS, question("How many days can I work remotely?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(Matchers.startsWith("FAKE:")))
                .andExpect(jsonPath("$.mode").value("LOCAL"))
                .andExpect(jsonPath("$.citations.length()").value(1))
                .andExpect(jsonPath("$.citations[0].sourceType").value("HR_POLICY"))
                .andExpect(jsonPath("$.citations[0].sourceId").value(remote.toString()))
                .andExpect(jsonPath("$.citations[0].title").value("Remote work policy"));
        assertThat(ai.answerCalls).hasValue(1);
    }

    @Test
    void citationsOutsideTheRetrievedPassagesAreDropped() throws Exception {
        publishedPolicy("Remote work policy", "Staff may work remotely two days a week.");
        publishedPolicy("Travel policy", "Book economy class for work travel.");
        ai.forcedCitations = List.of(1, 99);
        owner.post(POLICY_QUESTIONS, question("Can I work remotely?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.citations.length()").value(1));
    }

    @Test
    void nothingRetrievedAnswersWithoutTheModelButStillCountsTheRequest() throws Exception {
        owner.post(POLICY_QUESTIONS, question("How many days can I work remotely?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value(NOTHING_FOUND))
                .andExpect(jsonPath("$.citations").isEmpty())
                .andExpect(jsonPath("$.mode").value("LOCAL"));
        assertThat(ai.answerCalls).hasValue(0);
        assertThat(requests()).singleElement().satisfies(r -> {
            assertThat(r).containsEntry("feature", "POLICY_QUESTION").containsEntry("mode", "LOCAL")
                    .containsEntry("model", "bge-small-en-v1.5").containsEntry("prompt_version", "policy-v1")
                    .containsEntry("input_tokens", 0).containsEntry("output_tokens", 0);
            assertThat(r).containsEntry("sources", "");
        });
    }

    @Test
    void aQuestionMustBe3To500Characters() throws Exception {
        for (String bad : List.of("  ", "ab", "x".repeat(501))) {
            owner.post(POLICY_QUESTIONS, question(bad)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value("question"))
                    .andExpect(jsonPath("$.errors[0].message").value(QUESTION_RULE));
        }
        owner.post(POLICY_QUESTIONS, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("question"));
        owner.perform(MockMvcRequestBuilders.post(POLICY_QUESTIONS).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest());
        assertThat(ai.embedCalls).hasValue(0);
        assertThat(requests()).isEmpty();
    }

    @Test
    void aReplyDraftOnlyUsesArticlesTheCallerMayRead() throws Exception {
        UUID article = publishedArticle("Clearing a paper jam", "Open the rear tray and remove the jammed paper.");
        UUID id = ticket(owner, requester, "Printer paper jam", "Paper is jammed in the rear tray");
        // the owner can read articles: the draft cites the article
        owner.post("/api/v1/ai/helpdesk/tickets/" + id + "/reply-draft", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.draft").value(Matchers.startsWith("FAKE:")))
                .andExpect(jsonPath("$.grounded").value(true))
                .andExpect(jsonPath("$.citations[0].sourceType").value("KB_ARTICLE"))
                .andExpect(jsonPath("$.citations[0].sourceId").value(article.toString()))
                .andExpect(jsonPath("$.mode").value("LOCAL"));

        UUID agentRole = TestRoles.create(mvc, owner.session(), "AI agent", "ai.assistant.use", "helpdesk.ticket.read");
        Api agent = Api.login(mvc, members.create(ws.tenantId(), Set.of(agentRole)));
        agent.post("/api/v1/ai/helpdesk/tickets/" + id + "/reply-draft", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.draft").value(NOTHING_FOUND))
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.citations").isEmpty());

        UUID readerRole = TestRoles.create(mvc, owner.session(), "Reader", "helpdesk.ticket.read",
                "helpdesk.article.read");
        Api reader = Api.login(mvc, members.create(ws.tenantId(), Set.of(readerRole)));
        reader.post("/api/v1/ai/helpdesk/tickets/" + id + "/reply-draft", "").andExpect(status().isForbidden());
        reader.post("/api/v1/ai/helpdesk/tickets/" + id + "/triage", "").andExpect(status().isForbidden());
        reader.post(POLICY_QUESTIONS, question("Can I work remotely?")).andExpect(status().isForbidden());
        reader.get("/api/v1/ai/status").andExpect(status().isForbidden());
        assertThat(requests()).extracting(r -> r.get("feature")).containsExactly("TICKET_REPLY", "TICKET_REPLY");
    }

    @Test
    void aReplyDraftWithNoClearAnswerIsNotGroundedButNamesTheArticlesThatMayHelp() throws Exception {
        UUID article = publishedArticle("Clearing a paper jam", "Open the rear tray and remove the jammed paper.");
        UUID id = ticket(owner, requester, "Printer paper jam", "Paper is jammed in the rear tray");
        ai.forcedAnswer = "I couldn't find a clear answer in your workspace's documents. These may help:\n"
                + "- Clearing a paper jam";
        ai.forcedGrounded = false;
        owner.post("/api/v1/ai/helpdesk/tickets/" + id + "/reply-draft", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.draft").value(ai.forcedAnswer))
                .andExpect(jsonPath("$.grounded").value(false))
                .andExpect(jsonPath("$.citations[0].sourceId").value(article.toString()));
    }

    @Test
    void policyAnswerMarkersFollowTheListedSources() throws Exception {
        publishedPolicy("Remote work policy", "Staff may work remotely two days a week.");
        publishedPolicy("Travel policy", "Book economy class for work travel.");
        // only passage 2 is cited, so it is source [1]; the marker for passage 7 (never sent) is removed
        ai.forcedCitations = List.of(2);
        ai.forcedAnswer = "Book economy class for work travel. [2] Something else. [7]";
        owner.post(POLICY_QUESTIONS, question("Which class do I book for work travel?")).andExpect(status().isOk())
                .andExpect(jsonPath("$.answer").value("Book economy class for work travel. [1] Something else."))
                .andExpect(jsonPath("$.citations.length()").value(1));
    }

    @Test
    void triageSendsAtMost100CategoriesSoAnyNumberOfCategoriesWorks() throws Exception {
        for (int i = 0; i < 101; i++) {
            owner.post("/api/v1/helpdesk/categories", "{\"name\":\"Category " + i + "\"}")
                    .andExpect(status().isCreated());
        }
        UUID id = ticket(owner, requester, "Invoice charged twice", "My card was charged twice for one invoice");
        owner.post("/api/v1/ai/helpdesk/tickets/" + id + "/triage", "").andExpect(status().isOk());
        assertThat(ai.lastTriageCategories).hasSize(100);
    }

    @Test
    void triageSuggestsOnlyAnExistingCategoryAndAValidPriority() throws Exception {
        UUID billing = TestHelpDesk.category(owner, "Billing");
        owner.post("/api/v1/helpdesk/categories", "{\"name\":\"Login\"}").andExpect(status().isCreated());
        UUID id = ticket(owner, requester, "Invoice charged twice", "My card was charged twice for one invoice");

        ai.forcedCategoryId = billing.toString();
        owner.post("/api/v1/ai/helpdesk/tickets/" + id + "/triage", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(billing.toString()))
                .andExpect(jsonPath("$.categoryName").value("Billing"))
                .andExpect(jsonPath("$.priority").value("NORMAL"))
                .andExpect(jsonPath("$.reason").value("FAKE triage"))
                .andExpect(jsonPath("$.mode").value("LOCAL"));

        ai.forcedCategoryId = UUID.randomUUID().toString();
        owner.post("/api/v1/ai/helpdesk/tickets/" + id + "/triage", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.categoryName").value(Matchers.nullValue()))
                .andExpect(jsonPath("$.priority").value("NORMAL"));

        ai.forcedCategoryId = "not-a-uuid";
        owner.post("/api/v1/ai/helpdesk/tickets/" + id + "/triage", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.categoryId").value(Matchers.nullValue()));
        assertThat(requests()).extracting(r -> r.get("prompt_version")).containsOnly("triage-v1");
    }

    @Test
    void aTicketTheCallerCannotSeeIsNotFound() throws Exception {
        Workspace other = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("aigw2"));
        Api otherOwner = Api.login(mvc, other);
        TestHelpDesk.enable(otherOwner);
        UUID otherRequester = Api.id(otherOwner.post("/api/v1/persons", "{\"firstName\":\"B\",\"lastName\":\"B\"}"));
        UUID foreign = ticket(otherOwner, otherRequester, "Invoice charged twice", "Charged twice");
        for (UUID id : List.of(foreign, UUID.randomUUID())) {
            owner.post("/api/v1/ai/helpdesk/tickets/" + id + "/triage", "").andExpect(status().isNotFound());
            owner.post("/api/v1/ai/helpdesk/tickets/" + id + "/reply-draft", "").andExpect(status().isNotFound());
        }
        assertThat(ai.triageCalls).hasValue(0);
        assertThat(ai.embedCalls).hasValue(0);
    }

    @Test
    void turnedOffAiRefusesEveryFeature() throws Exception {
        UUID id = ticket(owner, requester, "Printer paper jam", "Jammed");
        owner.put("/api/v1/ai/settings", "{\"enabled\":false,\"monthlyLimit\":500,\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        owner.get("/api/v1/ai/status").andExpect(status().isOk()).andExpect(jsonPath("$.enabled").value(false));
        owner.post("/api/v1/ai/helpdesk/tickets/" + id + "/triage", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(TURNED_OFF));
        owner.post("/api/v1/ai/helpdesk/tickets/" + id + "/reply-draft", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(TURNED_OFF));
        owner.post(POLICY_QUESTIONS, question("Can I work remotely?")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(TURNED_OFF));
        // a missing ticket is still 404 first
        owner.post("/api/v1/ai/helpdesk/tickets/" + UUID.randomUUID() + "/triage", "")
                .andExpect(status().isNotFound());
        assertThat(requests()).isEmpty();
    }

    @Test
    void theMonthlyLimitIsEnforced() throws Exception {
        owner.put("/api/v1/ai/settings", "{\"enabled\":true,\"monthlyLimit\":1,\"version\":0}")
                .andExpect(status().isOk());
        owner.post(POLICY_QUESTIONS, question("Can I work remotely?")).andExpect(status().isOk());
        owner.post(POLICY_QUESTIONS, question("Can I work remotely?")).andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.detail").value(LIMIT_REACHED));
        assertThat(requests()).hasSize(1);
    }

    @Test
    void anUnreachableAiServiceIs503AndRecordsNothing() throws Exception {
        publishedPolicy("Remote work policy", "Staff may work remotely two days a week.");
        UUID id = ticket(owner, requester, "Invoice charged twice", "Charged twice");
        ai.down = true;
        owner.post(POLICY_QUESTIONS, question("Can I work remotely?")).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(UNAVAILABLE));
        owner.post("/api/v1/ai/helpdesk/tickets/" + id + "/triage", "").andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.detail").value(UNAVAILABLE));
        owner.post("/api/v1/ai/helpdesk/tickets/" + id + "/reply-draft", "")
                .andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.detail").value(UNAVAILABLE));
        owner.get("/api/v1/ai/status").andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.provider").value("UNAVAILABLE"));
        assertThat(requests()).isEmpty();
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'AiRequestCompleted'", Long.class)).isZero();

        ai.down = false;
        owner.get("/api/v1/ai/status").andExpect(jsonPath("$.provider").value("LOCAL"));
    }

    @Test
    void everyRequestIsRecordedAndAuditedWithoutTheQuestionText() throws Exception {
        UUID remote = publishedPolicy("Remote work policy", "Staff may work remotely two days a week.");
        String asked = "How many days can I work remotely from home?";
        owner.post(POLICY_QUESTIONS, question(asked)).andExpect(status().isOk());

        List<Map<String, Object>> rows = requests();
        assertThat(rows).singleElement().satisfies(r -> {
            assertThat(r).containsEntry("feature", "POLICY_QUESTION").containsEntry("user_id", ownerId())
                    .containsEntry("mode", "LOCAL").containsEntry("prompt_version", "policy-v1");
            assertThat(r).containsEntry("sources", remote.toString());
            assertThat(r.values()).noneMatch(v -> v != null && v.toString().contains(asked));
        });
        List<Map<String, Object>> audits = OwnerJdbc.ownerAs(ws.tenantId()).queryForList(
                "select entity_type, entity_id, actor_id, before::text as before, after::text as after, "
                        + "metadata::text as metadata, metadata ->> 'feature' as feature from audit_events "
                        + "where action = 'AiRequestCompleted'");
        assertThat(audits).singleElement().satisfies(a -> {
            assertThat(a).containsEntry("entity_type", "ai_request")
                    .containsEntry("entity_id", rows.getFirst().get("id").toString())
                    .containsEntry("actor_id", ownerId()).containsEntry("feature", "POLICY_QUESTION");
            assertThat((String) a.get("metadata")).contains(remote.toString(), "policy-v1", "latencyMs")
                    .doesNotContain(asked).doesNotContain("FAKE");
            assertThat(a.get("before")).isNull();
            assertThat(a.get("after")).isNull();
        });
    }
}
