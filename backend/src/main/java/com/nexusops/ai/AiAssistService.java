package com.nexusops.ai;

import com.nexusops.ai.client.AiAnswer;
import com.nexusops.ai.client.AiTriage;
import com.nexusops.ai.client.CategoryOption;
import com.nexusops.ai.client.Passage;
import com.nexusops.helpdesk.CategoryService;
import com.nexusops.helpdesk.Priority;
import com.nexusops.helpdesk.TicketService;
import com.nexusops.helpdesk.TicketView;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.ratelimit.RateLimits;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * The AI gateway's features: ticket triage, ticket reply drafts and HR policy questions. Each request is admitted in
 * the spec's order (400 → 403 → 404 → 409 → 429 → 503) with short reads, calls ai-service outside any transaction
 * (D1, D12), keeps only citations of passages it actually sent (D8), then records usage and audit (D10). AI output is
 * only ever returned, never applied (D13).
 */
@Service
public class AiAssistService {

    static final String TURNED_OFF = "AI assistance is turned off for this workspace.";
    static final String LIMIT_REACHED = "This workspace has used its AI requests for this month.";
    static final String UNAVAILABLE = "The AI service is unavailable. Try again shortly.";
    static final String QUESTION_RULE = "Ask a question of 3 to 500 characters.";
    static final String NOTHING_FOUND = "I couldn't find anything about that in your workspace's documents.";

    static final String TICKET_TRIAGE = "TICKET_TRIAGE";
    static final String TICKET_REPLY = "TICKET_REPLY";
    static final String POLICY_QUESTION = "POLICY_QUESTION";
    static final String KB_ARTICLE = "KB_ARTICLE";
    static final String HR_POLICY = "HR_POLICY";
    static final String MODE = "LOCAL";
    static final String MODEL = "bge-small-en-v1.5";
    static final String RATE_LIMIT_RULE = "ai";
    static final int TOP_K = 5;
    static final int QUESTION_MIN = 3;
    static final int QUESTION_MAX = 500;

    private final TicketService tickets;
    private final CategoryService categories;
    private final AiSettingsService settings;
    private final RateLimits rateLimits;
    private final AiClient ai;
    private final KnowledgeIndex index;
    private final AiUsage usage;

    AiAssistService(TicketService tickets, CategoryService categories, AiSettingsService settings,
            RateLimits rateLimits, AiClient ai, KnowledgeIndex index, AiUsage usage) {
        this.tickets = tickets;
        this.categories = categories;
        this.settings = settings;
        this.rateLimits = rateLimits;
        this.ai = ai;
        this.index = index;
        this.usage = usage;
    }

    /** A category (an existing one or none) and priority for the ticket; 404 when the caller can't see it. */
    public AiTriageView triage(UUID ticketId) {
        TicketView ticket = tickets.get(ticketId);
        admit();
        List<CategoryOption> options = categories.list(false).stream()
                .map(c -> new CategoryOption(c.id(), c.name())).toList();
        long started = System.nanoTime();
        AiTriage triage = call(() -> ai.triage(ticket.subject(), ticket.description(), options));
        CategoryOption category = category(triage.categoryId(), options);
        usage.record(new AiUsage.Completed(TICKET_TRIAGE, orDefault(triage.mode(), MODE),
                orDefault(triage.model(), MODEL), orDefault(triage.promptVersion(), "triage-v1"),
                triage.inputTokens(), triage.outputTokens(), elapsedMs(started), List.of()));
        return new AiTriageView(category == null ? null : category.id(), category == null ? null : category.name(),
                priority(triage.priority()), triage.reason(), orDefault(triage.mode(), MODE));
    }

    /** A reply draft from the knowledge-base articles the caller may read; 404 when the caller can't see the ticket. */
    public AiDraftView replyDraft(UUID ticketId) {
        TicketView ticket = tickets.get(ticketId);
        admit();
        String query = ticket.subject() + "\n" + Objects.requireNonNullElse(ticket.description(), "");
        Answered answered = answer(TICKET_REPLY, "reply-v1", query, Set.of(KB_ARTICLE));
        return new AiDraftView(answered.text(), answered.citations(), answered.mode());
    }

    /** An answer from the published HR policies. */
    public AiAnswerView policyQuestion(PolicyQuestion request) {
        String question = question(request == null ? null : request.question());
        admit();
        Answered answered = answer(POLICY_QUESTION, "policy-v1", question, Set.of(HR_POLICY));
        return new AiAnswerView(answered.text(), answered.citations(), answered.mode());
    }

    private record Answered(String text, List<AiCitation> citations, String mode) {}

    /**
     * Retrieves the caller's top passages and asks ai-service for an extractive answer. With nothing retrieved the
     * model is not called, but the request still counts (spec §5).
     */
    private Answered answer(String feature, String promptVersion, String query, Set<String> sourceTypes) {
        Set<String> permissions = CurrentAuthorities.codes(); // KnowledgeIndex maps hr.policy.manage (D2)
        long started = System.nanoTime();
        List<RetrievedChunk> chunks = call(() -> {
            List<float[]> vectors = ai.embed(List.of(query));
            if (vectors == null || vectors.size() != 1) {
                throw new AiUnavailableException("ai-service returned no query vector");
            }
            return index.search(vectors.getFirst(), sourceTypes, permissions, TOP_K);
        });
        if (chunks.isEmpty()) {
            usage.record(new AiUsage.Completed(feature, MODE, MODEL, promptVersion, 0, 0, elapsedMs(started),
                    List.of()));
            return new Answered(NOTHING_FOUND, List.of(), MODE);
        }
        List<Passage> passages = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            passages.add(new Passage(i + 1, chunks.get(i).title(), chunks.get(i).content()));
        }
        AiAnswer answer = call(() -> ai.answer(feature, query, passages));
        List<AiCitation> citations = citations(chunks, answer.citedPassages());
        String mode = orDefault(answer.mode(), MODE);
        usage.record(new AiUsage.Completed(feature, mode, orDefault(answer.model(), MODEL),
                orDefault(answer.promptVersion(), promptVersion), answer.inputTokens(), answer.outputTokens(),
                elapsedMs(started), citations.stream().map(AiCitation::sourceId).toList()));
        return new Answered(answer.answer(), citations, mode);
    }

    /** 409 when AI is off, 429 when this month's limit is used up or the caller's per-minute bucket is empty. */
    private void admit() {
        AiSettingsService.Settings current = settings.current();
        if (!current.enabled()) {
            throw ApiProblem.conflict(TURNED_OFF);
        }
        if (settings.usedThisMonth() >= current.monthlyLimit()) {
            throw ApiProblem.tooManyRequests(LIMIT_REACHED);
        }
        UUID user = TenantContext.userId().orElseThrow(() -> new IllegalStateException("No user bound"));
        rateLimits.checkUser(RATE_LIMIT_RULE, TenantContext.requireTenantId(), user);
    }

    private static <T> T call(Supplier<T> work) {
        try {
            return work.get();
        } catch (AiUnavailableException e) {
            throw ApiProblem.serviceUnavailable(UNAVAILABLE);
        }
    }

    static String question(String raw) {
        String value = raw == null ? "" : raw.strip();
        if (value.length() < QUESTION_MIN || value.length() > QUESTION_MAX) {
            throw ApiProblem.badRequestField("question", QUESTION_RULE);
        }
        return value;
    }

    /**
     * The sources of the cited passages, only for numbers {@code 1..chunks.size()} (D8), one per document, in passage
     * order.
     */
    static List<AiCitation> citations(List<RetrievedChunk> chunks, List<Integer> cited) {
        if (cited == null || cited.isEmpty()) {
            return List.of();
        }
        Set<Integer> wanted = new HashSet<>();
        for (Integer n : cited) {
            if (n != null && n >= 1 && n <= chunks.size()) {
                wanted.add(n);
            }
        }
        Set<UUID> seen = new HashSet<>();
        List<AiCitation> citations = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            RetrievedChunk chunk = chunks.get(i);
            if (wanted.contains(i + 1) && seen.add(chunk.sourceId())) {
                citations.add(new AiCitation(chunk.sourceType(), chunk.sourceId(), chunk.title()));
            }
        }
        return List.copyOf(citations);
    }

    /** The suggested category if it is one of {@code options}; anything else is no category (D8). */
    static CategoryOption category(String suggested, List<CategoryOption> options) {
        if (suggested == null) {
            return null;
        }
        UUID id;
        try {
            id = UUID.fromString(suggested.strip());
        } catch (IllegalArgumentException e) {
            return null;
        }
        return options.stream().filter(o -> o.id().equals(id)).findFirst().orElse(null);
    }

    /** One of LOW/NORMAL/HIGH/URGENT; anything else is NORMAL (D8). */
    static Priority priority(String suggested) {
        if (suggested == null) {
            return Priority.NORMAL;
        }
        try {
            return Priority.valueOf(suggested.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Priority.NORMAL;
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static int elapsedMs(long startedNanos) {
        return (int) Math.min(Integer.MAX_VALUE, (System.nanoTime() - startedNanos) / 1_000_000);
    }
}
