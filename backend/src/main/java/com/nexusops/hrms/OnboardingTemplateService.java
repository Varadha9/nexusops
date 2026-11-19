package com.nexusops.hrms;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.hrms.domain.OnboardingTemplate;
import com.nexusops.hrms.domain.OnboardingTemplateItem;
import com.nexusops.hrms.domain.OnboardingTemplateItemRepository;
import com.nexusops.hrms.domain.OnboardingTemplateRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Onboarding checklist templates (D10): unique names ignoring case, edited as a whole, archive/restore. */
@Service
public class OnboardingTemplateService {

    static final String UNKNOWN = "Choose an onboarding template in this workspace.";
    static final String NAME_TAKEN = "Another template already uses this name.";
    static final String NO_ITEMS = "Add at least one item.";
    static final String TOO_MANY_ITEMS = "Use at most 50 items.";
    static final String OWNER = "Choose who the task is for.";
    static final String OFFSET = "Use 0 to 365 days after the start date.";
    private static final int MAX_ITEMS = 50;
    private static final String LOCK = "hr-onboarding-templates";
    private static final String STANDARD = "Standard onboarding";

    private final OnboardingTemplateRepository templates;
    private final OnboardingTemplateItemRepository items;
    private final TenantLocks locks;
    private final AuditService audit;

    OnboardingTemplateService(OnboardingTemplateRepository templates, OnboardingTemplateItemRepository items,
            TenantLocks locks, AuditService audit) {
        this.templates = templates;
        this.items = items;
        this.locks = locks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<OnboardingTemplateView> list(boolean archived) {
        TenantContext.requireTenantId();
        return views(archived ? templates.findByArchivedAtIsNotNullOrderByNameAsc()
                : templates.findByArchivedAtIsNullOrderByNameAsc());
    }

    @Transactional
    public OnboardingTemplateView create(OnboardingTemplateCommand command) {
        TenantContext.requireTenantId();
        Draft d = validate(command);
        locks.lock(LOCK);
        if (templates.existsByNameKey(HrRecords.key(d.name()))) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        return insert(d);
    }

    @Transactional
    public OnboardingTemplateView update(UUID id, OnboardingTemplateCommand command, Long version) {
        OnboardingTemplate template = find(id);
        HrRecords.checkVersion(template.getVersion(), version);
        Draft d = validate(command);
        HrRecords.requireNotArchived(template.isArchived());
        locks.lock(LOCK);
        if (templates.existsByNameKeyAndIdNot(HrRecords.key(d.name()), id)) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        Map<String, Object> before = snapshot(template.getName(), items.findByTemplateIdOrderByPositionAsc(id).size());
        template.apply(d.name(), HrRecords.key(d.name()));
        replaceItems(id, d.items());
        template.touch();
        templates.flush();
        audit.record(AuditEntry.of("OnboardingTemplateUpdated", "OnboardingTemplate", id).withBefore(before)
                .withAfter(snapshot(d.name(), d.items().size())));
        return views(List.of(template)).getFirst();
    }

    @Transactional
    public OnboardingTemplateView archive(UUID id) {
        OnboardingTemplate template = find(id);
        if (!template.isArchived()) {
            template.archive(Instant.now());
            templates.flush();
            audit.record(AuditEntry.of("OnboardingTemplateArchived", "OnboardingTemplate", id)
                    .withBefore(Map.of("name", template.getName())));
        }
        return views(List.of(template)).getFirst();
    }

    @Transactional
    public OnboardingTemplateView restore(UUID id) {
        OnboardingTemplate template = find(id);
        if (template.isArchived()) {
            template.restore();
            templates.flush();
            audit.record(AuditEntry.of("OnboardingTemplateRestored", "OnboardingTemplate", id)
                    .withAfter(Map.of("name", template.getName())));
        }
        return views(List.of(template)).getFirst();
    }

    /** A new workspace's default template; a no-op when it already has templates (the signup transaction). */
    @Transactional
    public void seedDefaults() {
        TenantContext.requireTenantId();
        locks.lock(LOCK);
        if (templates.count() > 0) {
            return;
        }
        insert(new Draft(STANDARD, List.of(
                new OnboardingItemView("Prepare laptop and accounts", null, OnboardingOwner.HR, 0),
                new OnboardingItemView("Welcome meeting and team introductions", null, OnboardingOwner.MANAGER, 0),
                new OnboardingItemView("Read the employee handbook", null, OnboardingOwner.EMPLOYEE, 3),
                new OnboardingItemView("Complete required training", null, OnboardingOwner.EMPLOYEE, 14),
                new OnboardingItemView("30-day check-in", null, OnboardingOwner.MANAGER, 30))));
    }

    OnboardingTemplate resolve(UUID id, String field) {
        if (id == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN);
        }
        return templates.findById(id).orElseThrow(() -> ApiProblem.badRequestField(field, UNKNOWN));
    }

    List<OnboardingTemplateItem> items(UUID templateId) {
        return items.findByTemplateIdOrderByPositionAsc(templateId);
    }

    private record Draft(String name, List<OnboardingItemView> items) {}

    private Draft validate(OnboardingTemplateCommand c) {
        String name = HrRecords.collapse(Text.required(c.name(), 80, "name"));
        List<OnboardingItemCommand> raw = c.items();
        if (raw == null || raw.isEmpty()) {
            throw ApiProblem.badRequestField("items", NO_ITEMS);
        }
        if (raw.size() > MAX_ITEMS) {
            throw ApiProblem.badRequestField("items", TOO_MANY_ITEMS);
        }
        List<OnboardingItemView> checked = new ArrayList<>();
        for (int i = 0; i < raw.size(); i++) {
            OnboardingItemCommand item = raw.get(i);
            String at = "items[" + i + "].";
            if (item == null) {
                throw ApiProblem.badRequestField(at + "title", "Enter between 1 and 200 characters.");
            }
            String title = HrRecords.collapse(Text.required(item.title(), 200, at + "title"));
            String description = Text.optional(item.description(), 2000, at + "description");
            if (item.owner() == null) {
                throw ApiProblem.badRequestField(at + "owner", OWNER);
            }
            if (item.dueOffsetDays() == null || item.dueOffsetDays() < 0 || item.dueOffsetDays() > 365) {
                throw ApiProblem.badRequestField(at + "dueOffsetDays", OFFSET);
            }
            checked.add(new OnboardingItemView(title, description, item.owner(), item.dueOffsetDays()));
        }
        return new Draft(name, checked);
    }

    private OnboardingTemplateView insert(Draft d) {
        OnboardingTemplate template = templates.saveAndFlush(new OnboardingTemplate(Ids.newId(), d.name(),
                HrRecords.key(d.name())));
        replaceItems(template.getId(), d.items());
        audit.record(AuditEntry.of("OnboardingTemplateCreated", "OnboardingTemplate", template.getId())
                .withAfter(snapshot(d.name(), d.items().size())));
        return views(List.of(template)).getFirst();
    }

    private void replaceItems(UUID templateId, List<OnboardingItemView> list) {
        items.deleteByTemplate(templateId);
        items.flush();
        for (int i = 0; i < list.size(); i++) {
            OnboardingItemView item = list.get(i);
            items.save(new OnboardingTemplateItem(Ids.newId(), templateId, i, item.title(), item.description(),
                    item.owner(), item.dueOffsetDays()));
        }
        items.flush();
    }

    private List<OnboardingTemplateView> views(List<OnboardingTemplate> list) {
        Map<UUID, List<OnboardingTemplateItem>> byTemplate = items
                .findByTemplateIdInOrderByPositionAsc(list.stream().map(OnboardingTemplate::getId).toList()).stream()
                .collect(Collectors.groupingBy(OnboardingTemplateItem::getTemplateId));
        return list.stream().map(t -> new OnboardingTemplateView(t.getId(), t.getName(),
                byTemplate.getOrDefault(t.getId(), List.of()).stream()
                        .sorted(java.util.Comparator.comparingInt(OnboardingTemplateItem::getPosition))
                        .map(i -> new OnboardingItemView(i.getTitle(), i.getDescription(), i.getOwner(),
                                i.getDueOffsetDays()))
                        .toList(),
                t.getArchivedAt(), t.getVersion())).toList();
    }

    private OnboardingTemplate find(UUID id) {
        TenantContext.requireTenantId();
        return templates.findById(id).orElseThrow(() -> ApiProblem.notFound(HrRecords.NOT_FOUND));
    }

    private static Map<String, Object> snapshot(String name, int itemCount) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", name);
        values.put("itemCount", itemCount);
        return values;
    }
}
