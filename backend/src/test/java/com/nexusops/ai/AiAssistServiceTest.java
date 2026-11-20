package com.nexusops.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.ai.client.CategoryOption;
import com.nexusops.helpdesk.CategoryView;
import com.nexusops.helpdesk.Priority;
import com.nexusops.shared.web.ApiProblem;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

/** The gateway's pure checks on ai-service output (D8) and on the question. */
class AiAssistServiceTest {

    final UUID remote = UUID.randomUUID();
    final UUID travel = UUID.randomUUID();
    final List<RetrievedChunk> chunks = List.of(
            new RetrievedChunk("HR_POLICY", remote, "Remote work policy", "Two days a week.", 0.1),
            new RetrievedChunk("HR_POLICY", travel, "Travel policy", "Economy class.", 0.2),
            new RetrievedChunk("HR_POLICY", remote, "Remote work policy", "Ask your manager.", 0.3));

    @Test
    void citationsKeepOnlyRetrievedPassagesOncePerDocumentInPassageOrder() {
        assertThat(AiAssistService.citations(chunks, Arrays.asList(3, 2, 1, 99, 0, -1, null)))
                .containsExactly(new AiCitation("HR_POLICY", remote, "Remote work policy"),
                        new AiCitation("HR_POLICY", travel, "Travel policy"));
        assertThat(AiAssistService.citations(chunks, List.of(3)))
                .containsExactly(new AiCitation("HR_POLICY", remote, "Remote work policy"));
        assertThat(AiAssistService.citations(chunks, List.of(4, 99))).isEmpty();
        assertThat(AiAssistService.citations(chunks, null)).isEmpty();
        assertThat(AiAssistService.citations(List.of(), List.of(1))).isEmpty();
    }

    @Test
    void answerMarkersAreRenumberedToTheListedSources() {
        UUID other = UUID.randomUUID();
        List<RetrievedChunk> four = List.of(chunks.get(0), chunks.get(1), chunks.get(2),
                new RetrievedChunk("HR_POLICY", other, "Expenses policy", "Keep receipts.", 0.4));
        // passages 2 and 3 cited: the sources list is [Travel, Remote], so passage 2 is [1] and passage 3 is [2]
        List<Integer> cited = List.of(2, 3);
        List<AiCitation> citations = AiAssistService.citations(four, cited);
        assertThat(citations).extracting(AiCitation::title).containsExactly("Travel policy", "Remote work policy");
        assertThat(AiAssistService.renumber("Economy class. [2] Ask your manager. [3]", four, cited, citations))
                .isEqualTo("Economy class. [1] Ask your manager. [2]");
        // the "these may help" listing is ordered by score, not by source: its markers still follow the sources
        assertThat(AiAssistService.renumber("These may help:\n- Remote work policy [3]\n- Travel policy [2]", four,
                cited, citations)).isEqualTo("These may help:\n- Remote work policy [2]\n- Travel policy [1]");
        // markers for passages that weren't cited, or weren't sent, are removed
        assertThat(AiAssistService.renumber("Economy class. [2] Keep receipts. [4] Odd. [9] Zero. [0]", four,
                cited, citations)).isEqualTo("Economy class. [1] Keep receipts. Odd. Zero.");
    }

    @Test
    void twoPassagesOfOneDocumentShareItsNumber() {
        List<Integer> cited = List.of(1, 3);
        List<AiCitation> citations = AiAssistService.citations(chunks, cited);
        assertThat(citations).extracting(AiCitation::title).containsExactly("Remote work policy");
        assertThat(AiAssistService.renumber("Two days a week. [1] Ask your manager. [3]", chunks, cited, citations))
                .isEqualTo("Two days a week. [1] Ask your manager. [1]");
        assertThat(AiAssistService.renumber("No markers here.", chunks, cited, citations))
                .isEqualTo("No markers here.");
        assertThat(AiAssistService.renumber("Nothing cited. [1]", chunks, List.of(), List.of()))
                .isEqualTo("Nothing cited.");
    }

    @Test
    void triageSendsAtMost100CategoriesInTheWorkspacesOrder() {
        List<CategoryView> many = IntStream.range(0, 130)
                .mapToObj(i -> new CategoryView(UUID.randomUUID(), "Category " + i, null, null, i, null, 0)).toList();
        List<CategoryOption> options = AiAssistService.triageOptions(many);
        assertThat(options).hasSize(100);
        assertThat(options).extracting(CategoryOption::name).startsWith("Category 0", "Category 1")
                .endsWith("Category 99");
        assertThat(AiAssistService.triageOptions(many.subList(0, 3))).extracting(CategoryOption::id)
                .containsExactly(many.get(0).id(), many.get(1).id(), many.get(2).id());
    }

    @Test
    void triageCategoryMustBeOneOfTheWorkspacesCategories() {
        CategoryOption billing = new CategoryOption(UUID.randomUUID(), "Billing");
        List<CategoryOption> options = List.of(billing, new CategoryOption(UUID.randomUUID(), "Login"));
        assertThat(AiAssistService.category(billing.id().toString(), options)).isEqualTo(billing);
        assertThat(AiAssistService.category(UUID.randomUUID().toString(), options)).isNull();
        assertThat(AiAssistService.category("Billing", options)).isNull();
        assertThat(AiAssistService.category(null, options)).isNull();
    }

    @Test
    void triagePriorityFallsBackToNormal() {
        assertThat(AiAssistService.priority("URGENT")).isEqualTo(Priority.URGENT);
        assertThat(AiAssistService.priority(" low ")).isEqualTo(Priority.LOW);
        assertThat(AiAssistService.priority("CRITICAL")).isEqualTo(Priority.NORMAL);
        assertThat(AiAssistService.priority(null)).isEqualTo(Priority.NORMAL);
    }

    @Test
    void aQuestionIsTrimmedAndMustBe3To500Characters() {
        assertThat(AiAssistService.question("  Can I work remotely?  ")).isEqualTo("Can I work remotely?");
        assertThat(AiAssistService.question("abc")).isEqualTo("abc");
        assertThat(AiAssistService.question("x".repeat(500))).hasSize(500);
        for (String bad : Arrays.asList(null, "", "   ", " ab ", "x".repeat(501))) {
            assertThatThrownBy(() -> AiAssistService.question(bad)).isInstanceOfSatisfying(ApiProblem.class, e -> {
                assertThat(e.status().value()).isEqualTo(400);
                assertThat(e.errors()).containsExactly(
                        new ApiProblem.FieldError("question", "Ask a question of 3 to 500 characters."));
            });
        }
    }
}
