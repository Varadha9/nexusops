package com.nexusops.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.ai.client.CategoryOption;
import com.nexusops.helpdesk.Priority;
import com.nexusops.shared.web.ApiProblem;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
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
