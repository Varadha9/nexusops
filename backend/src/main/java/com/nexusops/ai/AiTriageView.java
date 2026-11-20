package com.nexusops.ai;

import com.nexusops.helpdesk.Priority;
import java.util.UUID;

/** A triage suggestion for a ticket; the category is an existing one or null (D8). Nothing is applied (D13). */
public record AiTriageView(UUID categoryId, String categoryName, Priority priority, String reason, String mode) {}
