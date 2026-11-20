package com.nexusops.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.knowledge.KnowledgeChanged;
import com.nexusops.support.Api;
import com.nexusops.support.FakeAiClient;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHelpDesk;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class KnowledgeIndexIT extends IntegrationTestSupport {

    static final String PAPER_JAM = "Open the rear tray of the printer. Pull the jammed paper out slowly.";

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired FakeAiClient ai;
    @Autowired KnowledgeIndex index;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ai.reset();
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("aikb"));
        owner = Api.login(mvc, ws);
        TestHelpDesk.enable(owner);
        TestHr.enable(owner);
    }

    @AfterEach
    void resetFake() {
        ai.reset();
    }

    private UUID article(String title, String body) throws Exception {
        return Api.id(owner.post("/api/v1/helpdesk/articles", "{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}")
                .andExpect(status().isCreated()));
    }

    private UUID policy(String title, String body) throws Exception {
        return Api.id(owner.post("/api/v1/hr/policies", "{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}")
                .andExpect(status().isCreated()));
    }

    private void action(String path, UUID id, String action, long version) throws Exception {
        owner.post(path + "/" + id + "/" + action, "{\"version\":" + version + "}").andExpect(status().isOk());
    }

    private List<Map<String, Object>> chunks(UUID sourceId) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForList("select source_type, chunk_index, title, content, "
                + "required_permission from ai_chunks where source_id = ? order by chunk_index", sourceId);
    }

    private List<RetrievedChunk> search(String query, Set<String> types, Set<String> permissions) {
        return TenantContext.callAs(ws.tenantId(),
                () -> index.search(FakeAiClient.vector(query), types, permissions, 5));
    }

    @Test
    void publishingAnArticleIndexesItsChunksWithTheReadPermissionAndTitle() throws Exception {
        UUID id = article("Clearing a paper jam", PAPER_JAM);
        assertThat(chunks(id)).isEmpty(); // drafts are not indexed
        action("/api/v1/helpdesk/articles", id, "publish", 0);

        assertThat(chunks(id)).singleElement().satisfies(c -> {
            assertThat(c).containsEntry("source_type", "KB_ARTICLE").containsEntry("chunk_index", 0)
                    .containsEntry("title", "Clearing a paper jam").containsEntry("content", PAPER_JAM)
                    .containsEntry("required_permission", "helpdesk.article.read");
        });
        assertThat(TenantContext.callAs(ws.tenantId(), index::chunkCount)).isEqualTo(1);
    }

    @Test
    void unpublishingOrArchivingRemovesTheChunksAndAnEditReplacesThem() throws Exception {
        String path = "/api/v1/helpdesk/articles";
        UUID unpublished = article("Resetting a password", "Use the forgot password link on the sign-in page.");
        action(path, unpublished, "publish", 0);
        assertThat(chunks(unpublished)).hasSize(1);
        action(path, unpublished, "unpublish", 1);
        assertThat(chunks(unpublished)).isEmpty();

        UUID archived = article("Booking a meeting room", "Rooms are booked from the calendar.");
        action(path, archived, "publish", 0);
        assertThat(chunks(archived)).hasSize(1);
        action(path, archived, "archive", 1);
        assertThat(chunks(archived)).isEmpty();

        UUID edited = article("Printer setup", "Plug the printer in. Install the driver.");
        action(path, edited, "publish", 0);
        assertThat(chunks(edited)).hasSize(1);
        String longBody = IntStream.range(0, 40)
                .mapToObj(i -> "Step " + i + " of the new printer setup guide covers one more setting.")
                .collect(Collectors.joining(" "));
        owner.put(path + "/" + edited, "{\"title\":\"Printer setup guide\",\"body\":\"" + longBody
                + "\",\"version\":1}").andExpect(status().isOk());
        List<Map<String, Object>> after = chunks(edited);
        assertThat(after).hasSize(Chunker.chunks(longBody).size()).hasSizeGreaterThan(1)
                .allSatisfy(c -> assertThat(c).containsEntry("title", "Printer setup guide"))
                .noneSatisfy(c -> assertThat((String) c.get("content")).contains("Install the driver"));
        assertThat(after.stream().map(c -> (Integer) c.get("chunk_index")))
                .containsExactlyElementsOf(IntStream.range(0, after.size()).boxed().toList());
    }

    @Test
    void whenAiServiceIsDownPublishingStillWorksAndARebuildIndexesLater() throws Exception {
        UUID id = article("Clearing a paper jam", PAPER_JAM);
        UUID policy = policy("Leave policy", "Annual leave is 20 days a year.");
        ai.down = true;
        action("/api/v1/helpdesk/articles", id, "publish", 0);
        action("/api/v1/hr/policies", policy, "publish", 0);
        assertThat(chunks(id)).isEmpty();
        assertThat(chunks(policy)).isEmpty();

        ai.down = false;
        KnowledgeIndex.RebuildResult result = TenantContext.callAs(ws.tenantId(), index::rebuild);
        assertThat(result).isEqualTo(new KnowledgeIndex.RebuildResult(2, 2));
        assertThat(chunks(id)).hasSize(1);
        assertThat(chunks(policy)).singleElement()
                .satisfies(c -> assertThat(c).containsEntry("required_permission", "hr.self.service"));
        assertThat(TenantContext.callAs(ws.tenantId(), index::chunkCount)).isEqualTo(2);
    }

    @Test
    void aRebuildThatCannotReachAiServiceKeepsTheExistingIndex() throws Exception {
        UUID id = article("Clearing a paper jam", PAPER_JAM);
        action("/api/v1/helpdesk/articles", id, "publish", 0);
        ai.down = true;
        assertThatThrownBy(() -> TenantContext.callAs(ws.tenantId(), index::rebuild))
                .isInstanceOf(AiUnavailableException.class);
        assertThat(chunks(id)).hasSize(1);
    }

    @Test
    void searchOnlyReturnsChunksTheCallerMayRead() throws Exception {
        UUID jam = article("Clearing a paper jam", PAPER_JAM);
        UUID vpn = article("Connecting to the VPN", "Install the VPN client and sign in with your work account.");
        UUID leave = policy("Leave policy", "Annual leave is 20 days a year. Ask your manager to approve leave.");
        action("/api/v1/helpdesk/articles", jam, "publish", 0);
        action("/api/v1/helpdesk/articles", vpn, "publish", 0);
        action("/api/v1/hr/policies", leave, "publish", 0);
        Set<String> both = Set.of("KB_ARTICLE", "HR_POLICY");

        assertThat(search("paper jam in the printer tray", both, Set.of("hr.self.service")))
                .allSatisfy(c -> assertThat(c.sourceType()).isEqualTo("HR_POLICY"));
        assertThat(search("paper jam in the printer tray", both, Set.of("helpdesk.ticket.read"))).isEmpty();

        List<RetrievedChunk> kb = search("paper jam in the printer tray", both, Set.of("helpdesk.article.read"));
        assertThat(kb).extracting(RetrievedChunk::sourceId).containsExactly(jam, vpn);
        assertThat(kb.getFirst().title()).isEqualTo("Clearing a paper jam");
        assertThat(kb.getFirst().distance()).isLessThan(kb.get(1).distance());

        // hr.policy.manage satisfies hr.self.service (D2); sourceTypes narrows the kinds of document
        assertThat(search("how many days of annual leave", both, Set.of("hr.policy.manage")))
                .extracting(RetrievedChunk::sourceId).containsExactly(leave);
        assertThat(search("paper jam", Set.of("HR_POLICY"), Set.of("helpdesk.article.read", "hr.self.service")))
                .extracting(RetrievedChunk::sourceId).containsExactly(leave);
    }

    interface ApiCall {
        void run() throws Exception;
    }

    /** Runs {@code call} to completion on another thread (its own request and transaction). */
    private static Runnable onAnotherThread(ApiCall call) {
        return () -> {
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread thread = Thread.ofPlatform().start(() -> {
                try {
                    call.run();
                } catch (Throwable e) {
                    failure.set(e);
                }
            });
            try {
                thread.join(30_000);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
            if (failure.get() != null) {
                throw new IllegalStateException(failure.get());
            }
        };
    }

    /** Publishes without indexing (ai-service down), so the test can drive one re-index itself. */
    private UUID publishedButNotIndexed() throws Exception {
        UUID id = article("Clearing a paper jam", PAPER_JAM);
        ai.down = true;
        action("/api/v1/helpdesk/articles", id, "publish", 0);
        ai.down = false;
        assertThat(chunks(id)).isEmpty();
        return id;
    }

    @Test
    void aSourceWithdrawnWhileItIsBeingEmbeddedIsNotIndexed() throws Exception {
        UUID id = publishedButNotIndexed();
        ai.onNextEmbed.set(onAnotherThread(() -> action("/api/v1/helpdesk/articles", id, "unpublish", 1)));

        TenantContext.runAs(ws.tenantId(), () -> index.reindex(new KnowledgeChanged("KB_ARTICLE", id)));

        assertThat(ai.onNextEmbed.get()).as("the unpublish ran during the embed").isNull();
        assertThat(chunks(id)).isEmpty();
    }

    @Test
    void aSourceEditedWhileItIsBeingEmbeddedKeepsOnlyTheNewChunks() throws Exception {
        UUID id = publishedButNotIndexed();
        ai.onNextEmbed.set(onAnotherThread(() -> owner.put("/api/v1/helpdesk/articles/" + id,
                "{\"title\":\"Clearing a paper jam\",\"body\":\"Lift the top cover first.\",\"version\":1}")
                .andExpect(status().isOk())));

        TenantContext.runAs(ws.tenantId(), () -> index.reindex(new KnowledgeChanged("KB_ARTICLE", id)));

        assertThat(ai.onNextEmbed.get()).as("the edit ran during the embed").isNull();
        assertThat(chunks(id)).singleElement()
                .satisfies(c -> assertThat(c).containsEntry("content", "Lift the top cover first."));
    }

    @Test
    void draftPoliciesAreNeverIndexed() throws Exception {
        UUID draft = policy("Remote work policy", "Staff may work remotely two days a week.");
        owner.put("/api/v1/hr/policies/" + draft, "{\"title\":\"Remote work policy\",\"body\":\"Three days a week.\","
                + "\"version\":0}").andExpect(status().isOk());
        assertThat(chunks(draft)).isEmpty();
        assertThat(TenantContext.callAs(ws.tenantId(), index::rebuild))
                .isEqualTo(new KnowledgeIndex.RebuildResult(0, 0));
        assertThat(chunks(draft)).isEmpty();
    }

    @Test
    void anotherTenantsChunksAreNeverSearched() throws Exception {
        UUID jam = article("Clearing a paper jam", PAPER_JAM);
        action("/api/v1/helpdesk/articles", jam, "publish", 0);

        Workspace other = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("aikb2"));
        assertThat(TenantContext.callAs(other.tenantId(), () -> index.search(FakeAiClient.vector(PAPER_JAM),
                Set.of("KB_ARTICLE"), Set.of("helpdesk.article.read"), 5))).isEmpty();
        assertThat(TenantContext.callAs(other.tenantId(), index::chunkCount)).isZero();
    }
}
