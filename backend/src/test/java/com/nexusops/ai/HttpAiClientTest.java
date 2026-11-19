package com.nexusops.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.nexusops.ai.client.AiAnswer;
import com.nexusops.ai.client.AiTriage;
import com.nexusops.ai.client.CategoryOption;
import com.nexusops.ai.client.Passage;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** The JSON contract with ai-service (spec §5) and the D12 failure mapping, against a local stub server. */
class HttpAiClientTest {

    HttpServer server;
    final Map<String, String> requests = new ConcurrentHashMap<>();
    final Map<String, String> responses = new ConcurrentHashMap<>();
    final Map<String, Integer> statuses = new ConcurrentHashMap<>();
    HttpAiClient client;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String path = exchange.getRequestURI().getPath();
            requests.put(path, new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = responses.getOrDefault(path, "{}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(statuses.getOrDefault(path, 200), body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        client = new HttpAiClient(new AiProperties("http://127.0.0.1:" + server.getAddress().getPort()));
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static String vector() {
        return IntStream.range(0, 384).mapToObj(i -> i == 0 ? "1.0" : "0.0").collect(Collectors.joining(",", "[", "]"));
    }

    @Test
    void embedSendsTextsAndReadsVectors() {
        responses.put("/embed", "{\"vectors\":[" + vector() + "," + vector() + "]}");
        List<float[]> vectors = client.embed(List.of("one", "two"));
        assertThat(requests.get("/embed")).isEqualTo("{\"texts\":[\"one\",\"two\"]}");
        assertThat(vectors).hasSize(2).allSatisfy(v -> assertThat(v).hasSize(384).startsWith(1.0f));
    }

    @Test
    void embedRejectsAWrongNumberOrSizeOfVectors() {
        responses.put("/embed", "{\"vectors\":[" + vector() + "]}");
        assertThatThrownBy(() -> client.embed(List.of("one", "two"))).isInstanceOf(AiUnavailableException.class);
        responses.put("/embed", "{\"vectors\":[[0.1,0.2]]}");
        assertThatThrownBy(() -> client.embed(List.of("one"))).isInstanceOf(AiUnavailableException.class);
    }

    @Test
    void answerUsesTheCamelCaseContractAndCapsPassageText() {
        responses.put("/answer", "{\"answer\":\"Leave is 20 days [1].\",\"citedPassages\":[1],\"mode\":\"LOCAL\","
                + "\"model\":\"bge-small-en-v1.5\",\"promptVersion\":\"policy-v1\",\"inputTokens\":0,"
                + "\"outputTokens\":0}");
        AiAnswer answer = client.answer("POLICY_QUESTION", "How much leave?",
                List.of(new Passage(1, "Leave policy", "x".repeat(2000))));
        assertThat(answer).isEqualTo(new AiAnswer("Leave is 20 days [1].", List.of(1), "LOCAL", "bge-small-en-v1.5",
                "policy-v1", 0, 0));
        assertThat(requests.get("/answer")).startsWith("{\"task\":\"POLICY_QUESTION\",\"question\":\"How much leave?\","
                + "\"passages\":[{\"n\":1,\"title\":\"Leave policy\",\"text\":\"xxx")
                .contains("\"text\":\"" + "x".repeat(Chunker.MAX_LENGTH) + "\"");
    }

    @Test
    void triageSendsCategoriesAndReadsANullCategory() {
        UUID billing = UUID.randomUUID();
        responses.put("/triage", "{\"categoryId\":null,\"priority\":\"URGENT\",\"reason\":\"Mentions \\\"outage\\\".\","
                + "\"mode\":\"LOCAL\",\"model\":\"bge-small-en-v1.5\",\"promptVersion\":\"triage-v1\","
                + "\"inputTokens\":0,\"outputTokens\":0}");
        AiTriage triage = client.triage("Outage", "Everything is down",
                List.of(new CategoryOption(billing, "Billing")));
        assertThat(triage).isEqualTo(new AiTriage(null, "URGENT", "Mentions \"outage\".", "LOCAL", "bge-small-en-v1.5",
                "triage-v1", 0, 0));
        assertThat(requests.get("/triage")).isEqualTo("{\"subject\":\"Outage\",\"description\":\"Everything is down\","
                + "\"categories\":[{\"id\":\"" + billing + "\",\"name\":\"Billing\"}]}");
    }

    @Test
    void healthReadsProviderAndModel() {
        responses.put("/health", "{\"status\":\"ok\",\"service\":\"ai-service\",\"provider\":\"LOCAL\","
                + "\"embeddingModel\":\"bge-small-en-v1.5\"}");
        assertThat(client.health()).satisfies(h -> {
            assertThat(h.provider()).isEqualTo("LOCAL");
            assertThat(h.embeddingModel()).isEqualTo("bge-small-en-v1.5");
        });
    }

    @Test
    void errorsAndUnreachableServiceBecomeAiUnavailable() {
        statuses.put("/answer", 500);
        assertThatThrownBy(() -> client.answer("TICKET_REPLY", "q", List.of()))
                .isInstanceOf(AiUnavailableException.class);
        statuses.put("/triage", 422);
        assertThatThrownBy(() -> client.triage("s", "d", List.of())).isInstanceOf(AiUnavailableException.class);
        responses.put("/health", "not json");
        assertThatThrownBy(() -> client.health()).isInstanceOf(AiUnavailableException.class);

        server.stop(0);
        assertThatThrownBy(() -> client.embed(List.of("x"))).isInstanceOf(AiUnavailableException.class);
        assertThatThrownBy(() -> client.health()).isInstanceOf(AiUnavailableException.class);
    }
}
