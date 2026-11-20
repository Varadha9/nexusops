package com.nexusops.ai;

import com.nexusops.ai.client.AiAnswer;
import com.nexusops.ai.client.AiHealth;
import com.nexusops.ai.client.AiTriage;
import com.nexusops.ai.client.CategoryOption;
import com.nexusops.ai.client.Passage;
import java.util.List;

/** The stateless ai-service (D1). Every method throws {@link AiUnavailableException} when it can't be used. */
public interface AiClient {

    /** One 384-dimension vector per text, in order. Callers send at most 64 texts per call. */
    List<float[]> embed(List<String> texts);

    /** Extractive answer for {@code task} ({@code TICKET_REPLY} or {@code POLICY_QUESTION}) from the passages. */
    AiAnswer answer(String task, String question, List<Passage> passages);

    /** Category (one of {@code categories}, or null), priority and reason for a ticket. */
    AiTriage triage(String subject, String description, List<CategoryOption> categories);

    /** Provider status, with a short timeout. */
    AiHealth health();
}
