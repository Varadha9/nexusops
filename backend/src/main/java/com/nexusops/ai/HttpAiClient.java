package com.nexusops.ai;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.nexusops.ai.client.AiAnswer;
import com.nexusops.ai.client.AiHealth;
import com.nexusops.ai.client.AiTriage;
import com.nexusops.ai.client.CategoryOption;
import com.nexusops.ai.client.Passage;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

/**
 * {@link AiClient} over ai-service's JSON API (spec §5). Connect timeout 2 s, read timeout 20 s (health: 2 s). Any
 * transport failure, timeout, non-2xx status or unusable body becomes {@link AiUnavailableException} (D12). Inputs are
 * capped at ai-service's own limits so a long ticket or passage can't turn into a 422.
 */
@Component
@EnableConfigurationProperties(AiProperties.class)
public class HttpAiClient implements AiClient {

    private static final Logger log = LoggerFactory.getLogger(HttpAiClient.class);

    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(2);
    static final Duration READ_TIMEOUT = Duration.ofSeconds(20);
    static final Duration HEALTH_READ_TIMEOUT = Duration.ofSeconds(2);
    static final int DIMENSIONS = 384;
    static final int MAX_EMBED_BATCH = 64;
    static final int MAX_EMBED_TEXT = 4_000;
    static final int MAX_QUESTION = 4_000;
    static final int MAX_PASSAGE_TITLE = 200;
    static final int MAX_SUBJECT = 300;
    static final int MAX_DESCRIPTION = 20_000;

    private final RestClient client;
    private final RestClient healthClient;

    public HttpAiClient(AiProperties properties) {
        this.client = restClient(properties.serviceUrl(), READ_TIMEOUT);
        this.healthClient = restClient(properties.serviceUrl(), HEALTH_READ_TIMEOUT);
    }

    private static RestClient restClient(String baseUrl, Duration readTimeout) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(readTimeout);
        return RestClient.builder().baseUrl(baseUrl).requestFactory(factory).build();
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        if (texts.isEmpty()) {
            return List.of();
        }
        List<float[]> vectors = new ArrayList<>(texts.size());
        for (int from = 0; from < texts.size(); from += MAX_EMBED_BATCH) {
            List<String> batch = texts.subList(from, Math.min(from + MAX_EMBED_BATCH, texts.size())).stream()
                    .map(t -> cap(t, MAX_EMBED_TEXT)).toList();
            EmbedResponse response = post("/embed", new EmbedRequest(batch), EmbedResponse.class);
            if (response.vectors() == null || response.vectors().size() != batch.size()
                    || response.vectors().stream().anyMatch(v -> v == null || v.length != DIMENSIONS)) {
                throw unavailable("/embed", "unexpected vectors", null);
            }
            vectors.addAll(response.vectors());
        }
        return vectors;
    }

    @Override
    public AiAnswer answer(String task, String question, List<Passage> passages) {
        List<Passage> capped = passages.stream()
                .map(p -> new Passage(p.n(), cap(p.title(), MAX_PASSAGE_TITLE), cap(p.text(), Chunker.MAX_LENGTH)))
                .toList();
        AnswerResponse r = post("/answer", new AnswerRequest(task, cap(question, MAX_QUESTION), capped),
                AnswerResponse.class);
        if (r.answer() == null) {
            throw unavailable("/answer", "no answer", null);
        }
        return new AiAnswer(r.answer(), r.citedPassages() == null ? List.of() : r.citedPassages(), r.mode(), r.model(),
                r.promptVersion(), r.inputTokens(), r.outputTokens());
    }

    @Override
    public AiTriage triage(String subject, String description, List<CategoryOption> categories) {
        List<CategoryRequest> options = categories.stream()
                .map(c -> new CategoryRequest(c.id().toString(), c.name())).toList();
        TriageResponse r = post("/triage", new TriageRequest(cap(subject, MAX_SUBJECT),
                cap(description == null ? "" : description, MAX_DESCRIPTION), options), TriageResponse.class);
        if (r.priority() == null) {
            throw unavailable("/triage", "no priority", null);
        }
        return new AiTriage(r.categoryId(), r.priority(), r.reason(), r.mode(), r.model(), r.promptVersion(),
                r.inputTokens(), r.outputTokens());
    }

    @Override
    public AiHealth health() {
        HealthResponse r;
        try {
            r = healthClient.get().uri("/health").retrieve().body(HealthResponse.class);
        } catch (RestClientException e) {
            throw unavailable("/health", reason(e), safeCause(e));
        }
        if (r == null || r.provider() == null) {
            throw unavailable("/health", "empty body", null);
        }
        return new AiHealth(r.provider(), r.embeddingModel());
    }

    private <T> T post(String path, Object body, Class<T> type) {
        T response;
        try {
            response = client.post().uri(path).contentType(MediaType.APPLICATION_JSON).body(body).retrieve().body(type);
        } catch (RestClientException e) {
            throw unavailable(path, reason(e), safeCause(e));
        }
        if (response == null) {
            throw unavailable(path, "empty body", null);
        }
        return response;
    }

    private static AiUnavailableException unavailable(String path, String reason, Throwable cause) {
        log.warn("ai-service {} failed: {}", path, reason);
        return new AiUnavailableException("ai-service " + path + " failed: " + reason, cause);
    }

    /**
     * Status code or exception type only: a response exception's message embeds the body, and ai-service's 422 bodies
     * echo the offending input (question or ticket text), which must not reach logs.
     */
    static String reason(RestClientException e) {
        if (e instanceof RestClientResponseException response) {
            return "HTTP " + response.getStatusCode().value();
        }
        Throwable root = e.getMostSpecificCause();
        return root == null ? e.getClass().getSimpleName() : root.getClass().getSimpleName();
    }

    /** Only transport failures are kept as the cause: response and parse errors can carry body text. */
    private static Throwable safeCause(RestClientException e) {
        return e instanceof ResourceAccessException ? e : null;
    }

    private static String cap(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    record EmbedRequest(List<String> texts) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record EmbedResponse(List<float[]> vectors) {}

    record AnswerRequest(String task, String question, List<Passage> passages) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record AnswerResponse(String answer, List<Integer> citedPassages, String mode, String model, String promptVersion,
            int inputTokens, int outputTokens) {}

    record CategoryRequest(String id, String name) {}

    record TriageRequest(String subject, String description, List<CategoryRequest> categories) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record TriageResponse(String categoryId, String priority, String reason, String mode, String model,
            String promptVersion, int inputTokens, int outputTokens) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record HealthResponse(String status, String service, String provider, String embeddingModel) {}
}
