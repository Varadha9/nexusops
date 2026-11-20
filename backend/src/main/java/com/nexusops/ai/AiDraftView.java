package com.nexusops.ai;

import java.util.List;

/** A reply draft for a ticket, unsent (D13), with the knowledge-base articles it cites. */
public record AiDraftView(String draft, List<AiCitation> citations, String mode) {}
