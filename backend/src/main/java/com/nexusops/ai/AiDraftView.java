package com.nexusops.ai;

import java.util.List;

/**
 * A reply draft for a ticket, unsent (D13), with the knowledge-base articles it cites. {@code grounded} is true only
 * when {@code draft} is a reply built from article sentences; otherwise {@code draft} is a message for the agent
 * ("nothing found", or "no clear answer" with the articles that may help), never text for the customer.
 */
public record AiDraftView(String draft, List<AiCitation> citations, boolean grounded, String mode) {}
