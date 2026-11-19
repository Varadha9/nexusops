package com.nexusops.support;

import com.nexusops.ai.AiClient;
import com.nexusops.ai.AiUnavailableException;
import com.nexusops.ai.client.AiAnswer;
import com.nexusops.ai.client.AiHealth;
import com.nexusops.ai.client.AiTriage;
import com.nexusops.ai.client.CategoryOption;
import com.nexusops.ai.client.Passage;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.zip.CRC32;

/**
 * Deterministic stand-in for ai-service in ITs: a bag-of-words embedding (crc32 of each lower-cased word modulo 384,
 * L2-normalised — the same as ai-service's test embedder), so texts sharing words are close. Knobs are public and
 * reset by {@link #reset()}; tests that change them must call it (e.g. in {@code @AfterEach}).
 */
public class FakeAiClient implements AiClient {

    public static final int DIMENSIONS = 384;
    public static final String MODEL = "bge-small-en-v1.5";
    private static final Pattern WORD = Pattern.compile("[a-z0-9']+");

    /** Every method throws {@link AiUnavailableException}. */
    public volatile boolean down;
    /** Passage numbers {@link #answer} cites; null cites every passage. */
    public volatile List<Integer> forcedCitations;
    /** Category id {@link #triage} returns; null returns the first category's id (or null when there are none). */
    public volatile String forcedCategoryId;
    public final AtomicInteger embedCalls = new AtomicInteger();
    public final AtomicInteger answerCalls = new AtomicInteger();
    public final AtomicInteger triageCalls = new AtomicInteger();

    public void reset() {
        down = false;
        forcedCitations = null;
        forcedCategoryId = null;
        embedCalls.set(0);
        answerCalls.set(0);
        triageCalls.set(0);
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        embedCalls.incrementAndGet();
        failIfDown();
        return texts.stream().map(FakeAiClient::vector).toList();
    }

    @Override
    public AiAnswer answer(String task, String question, List<Passage> passages) {
        answerCalls.incrementAndGet();
        failIfDown();
        List<Integer> cited = forcedCitations != null ? List.copyOf(forcedCitations)
                : passages.stream().map(Passage::n).toList();
        return new AiAnswer("FAKE: " + passages.size() + " passages", cited, "LOCAL", MODEL,
                "TICKET_REPLY".equals(task) ? "reply-v1" : "policy-v1", 0, 0);
    }

    @Override
    public AiTriage triage(String subject, String description, List<CategoryOption> categories) {
        triageCalls.incrementAndGet();
        failIfDown();
        String categoryId = forcedCategoryId != null ? forcedCategoryId
                : categories.isEmpty() ? null : categories.getFirst().id().toString();
        return new AiTriage(categoryId, "NORMAL", "FAKE triage", "LOCAL", MODEL, "triage-v1", 0, 0);
    }

    @Override
    public AiHealth health() {
        failIfDown();
        return new AiHealth("LOCAL", MODEL);
    }

    /** The embedding of {@code text}, for tests that query the index directly. */
    public static float[] vector(String text) {
        float[] v = new float[DIMENSIONS];
        Matcher m = WORD.matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            CRC32 crc = new CRC32();
            crc.update(m.group().getBytes(StandardCharsets.UTF_8));
            v[(int) (crc.getValue() % DIMENSIONS)] += 1f;
        }
        double norm = Math.sqrt(IntStream.range(0, DIMENSIONS).mapToDouble(i -> v[i] * v[i]).sum());
        if (norm > 0) {
            for (int i = 0; i < DIMENSIONS; i++) {
                v[i] = (float) (v[i] / norm);
            }
        }
        return v;
    }

    private void failIfDown() {
        if (down) {
            throw new AiUnavailableException("FakeAiClient is down");
        }
    }
}
