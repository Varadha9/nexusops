package com.nexusops.ai.web;

import com.nexusops.ai.AiAnswerView;
import com.nexusops.ai.AiAssistService;
import com.nexusops.ai.AiDraftView;
import com.nexusops.ai.AiRebuildView;
import com.nexusops.ai.AiSettingsCommand;
import com.nexusops.ai.AiSettingsService;
import com.nexusops.ai.AiSettingsView;
import com.nexusops.ai.AiStatusView;
import com.nexusops.ai.AiTriageView;
import com.nexusops.ai.PolicyQuestion;
import java.util.UUID;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** AI assistance (D9–D13). Feature routes also need the feature's own read permission (D11). */
@RestController
@RequestMapping("/api/v1/ai")
class AiController {

    private final AiAssistService assist;
    private final AiSettingsService settings;

    AiController(AiAssistService assist, AiSettingsService settings) {
        this.assist = assist;
        this.settings = settings;
    }

    record AiSettingsRequest(Boolean enabled, Integer monthlyLimit, Long version) {
        AiSettingsCommand command() {
            return new AiSettingsCommand(enabled, monthlyLimit);
        }
    }

    @GetMapping("/status")
    @PreAuthorize("hasAuthority('ai.assistant.use')")
    AiStatusView status() {
        return settings.status();
    }

    @GetMapping("/settings")
    @PreAuthorize("hasAuthority('ai.settings.manage')")
    AiSettingsView settings() {
        return settings.view();
    }

    @PutMapping("/settings")
    @PreAuthorize("hasAuthority('ai.settings.manage')")
    AiSettingsView updateSettings(@RequestBody AiSettingsRequest request) {
        return settings.update(request.command(), request.version());
    }

    @PostMapping("/index/rebuild")
    @PreAuthorize("hasAuthority('ai.settings.manage')")
    AiRebuildView rebuild() {
        return settings.rebuild();
    }

    @PostMapping("/helpdesk/tickets/{id}/triage")
    @PreAuthorize("hasAuthority('ai.assistant.use') and hasAuthority('helpdesk.ticket.read')")
    AiTriageView triage(@PathVariable UUID id) {
        return assist.triage(id);
    }

    @PostMapping("/helpdesk/tickets/{id}/reply-draft")
    @PreAuthorize("hasAuthority('ai.assistant.use') and hasAuthority('helpdesk.ticket.read')")
    AiDraftView replyDraft(@PathVariable UUID id) {
        return assist.replyDraft(id);
    }

    @PostMapping("/hr/policy-questions")
    @PreAuthorize("hasAuthority('ai.assistant.use') and hasAnyAuthority('hr.self.service', 'hr.policy.manage')")
    AiAnswerView policyQuestion(@RequestBody PolicyQuestion request) {
        return assist.policyQuestion(request);
    }
}
