package com.nexusops.ai;

import java.util.List;

/** An answer to an HR policy question with the policies it cites. */
public record AiAnswerView(String answer, List<AiCitation> citations, String mode) {}
