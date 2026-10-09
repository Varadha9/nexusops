package com.nexusops.hrms;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.hrms.domain.HrPolicy;
import com.nexusops.hrms.domain.HrPolicyRepository;
import com.nexusops.identity.Members;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.security.CurrentAuthorities;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** HR policy library (D16). Without hr.policy.manage only published policies exist. */
@Service
public class PolicyService {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String ARCHIVED = "This record is archived.";

    private final HrPolicyRepository policies;
    private final HrQueries queries;
    private final Members members;
    private final AuditService audit;

    PolicyService(HrPolicyRepository policies, HrQueries queries, Members members, AuditService audit) {
        this.policies = policies;
        this.queries = queries;
        this.members = members;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public PageResponse<PolicySummary> list(String q, PolicyStatus status, Integer page, Integer size) {
        TenantContext.requireTenantId();
        List<PolicyStatus> statuses;
        if (!canManage()) {
            statuses = status == null || status == PolicyStatus.PUBLISHED ? List.of(PolicyStatus.PUBLISHED) : List.of();
        } else {
            statuses = status == null ? List.of(PolicyStatus.DRAFT, PolicyStatus.PUBLISHED) : List.of(status);
        }
        Pageable paging = Paging.of(page, size);
        if (statuses.isEmpty()) {
            return new PageResponse<>(List.of(), paging.getPageNumber(), paging.getPageSize(), 0);
        }
        HrQueries.Page found = queries.policyPage(q, statuses, paging.getPageSize(), paging.getOffset());
        return new PageResponse<>(summaries(found.ids()), paging.getPageNumber(), paging.getPageSize(), found.total());
    }

    @Transactional(readOnly = true)
    public PolicyView get(UUID id) {
        return view(find(id));
    }

    @Transactional
    public PolicyView create(PolicyCommand command) {
        TenantContext.requireTenantId();
        Fields f = validate(command);
        HrPolicy policy = new HrPolicy(Ids.newId(), f.title(), f.body(), TenantContext.userId().orElse(null));
        policies.saveAndFlush(policy);
        audit.record(AuditEntry.of("PolicyCreated", "HrPolicy", policy.getId()).withAfter(snapshot(policy)));
        return view(policy);
    }

    @Transactional
    public PolicyView update(UUID id, PolicyCommand command, Long version) {
        HrPolicy policy = find(id);
        checkVersion(policy, version);
        Fields f = validate(command);
        requireNotArchived(policy);
        Map<String, Object> before = snapshot(policy);
        policy.apply(f.title(), f.body());
        policies.flush();
        audit.record(AuditEntry.of("PolicyUpdated", "HrPolicy", id).withBefore(before).withAfter(snapshot(policy)));
        return view(policy);
    }

    @Transactional
    public PolicyView publish(UUID id, Long version) {
        HrPolicy policy = find(id);
        checkVersion(policy, version);
        requireNotArchived(policy);
        if (policy.getStatus() != PolicyStatus.PUBLISHED) {
            policy.publish(Instant.now());
            policies.flush();
            audit.record(AuditEntry.of("PolicyPublished", "HrPolicy", id).withAfter(Map.of("title", policy.getTitle())));
        }
        return view(policy);
    }

    @Transactional
    public PolicyView unpublish(UUID id, Long version) {
        HrPolicy policy = find(id);
        checkVersion(policy, version);
        requireNotArchived(policy);
        if (policy.getStatus() == PolicyStatus.PUBLISHED) {
            policy.unpublish(Instant.now());
            policies.flush();
            audit.record(AuditEntry.of("PolicyUnpublished", "HrPolicy", id).withAfter(Map.of("title", policy.getTitle())));
        }
        return view(policy);
    }

    @Transactional
    public PolicyView archive(UUID id, Long version) {
        HrPolicy policy = find(id);
        checkVersion(policy, version);
        if (policy.getStatus() != PolicyStatus.ARCHIVED) {
            policy.archive(Instant.now());
            policies.flush();
            audit.record(AuditEntry.of("PolicyArchived", "HrPolicy", id).withBefore(snapshot(policy)));
        }
        return view(policy);
    }

    /** Summaries in the order of {@code ids} (search ranking). */
    private List<PolicySummary> summaries(List<UUID> ids) {
        Map<UUID, HrPolicy> byId = policies.findAllById(ids).stream()
                .collect(Collectors.toMap(HrPolicy::getId, Function.identity()));
        return ids.stream().map(byId::get).filter(Objects::nonNull)
                .map(p -> new PolicySummary(p.getId(), p.getTitle(), excerpt(p.getBody()), p.getStatus(),
                        p.getPublishedAt(), p.getUpdatedAt()))
                .toList();
    }

    private record Fields(String title, String body) {}

    private static Fields validate(PolicyCommand c) {
        String title = Text.required(c.title(), 200, "title").replaceAll("\\s+", " ");
        String body = Text.required(c.body(), 50_000, "body");
        return new Fields(title, body);
    }

    /** Drafts and archived policies don't exist for readers without manage (404, not 403: no leak of their titles). */
    private HrPolicy find(UUID id) {
        TenantContext.requireTenantId();
        HrPolicy policy = policies.findById(id).orElseThrow(() -> ApiProblem.notFound(NOT_FOUND));
        if (policy.getStatus() != PolicyStatus.PUBLISHED && !canManage()) {
            throw ApiProblem.notFound(NOT_FOUND);
        }
        return policy;
    }

    private static boolean canManage() {
        return CurrentAuthorities.has(HrPermissions.POLICY_MANAGE);
    }

    private static void checkVersion(HrPolicy policy, Long version) {
        if (version == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (policy.getVersion() != version) {
            throw ApiProblem.conflict(STALE);
        }
    }

    private static void requireNotArchived(HrPolicy policy) {
        if (policy.getStatus() == PolicyStatus.ARCHIVED) {
            throw ApiProblem.conflict(ARCHIVED);
        }
    }

    private PolicyView view(HrPolicy p) {
        Members.Member author = p.getAuthorId() == null ? null : members.findAll(List.of(p.getAuthorId())).get(p.getAuthorId());
        return new PolicyView(p.getId(), p.getTitle(), p.getBody(), p.getStatus(),
                author == null ? null : new MemberRef(author.id(), author.name()), p.getPublishedAt(), p.getCreatedAt(),
                p.getUpdatedAt(), p.getVersion());
    }

    private static String excerpt(String body) {
        String flat = body.replaceAll("\\s+", " ").strip();
        return flat.length() <= 200 ? flat : flat.substring(0, 199) + "…";
    }

    private static Map<String, Object> snapshot(HrPolicy p) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", p.getTitle());
        values.put("status", p.getStatus().name());
        return values;
    }
}
