package com.nexusops.hrms;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.hrms.domain.Department;
import com.nexusops.hrms.domain.JobRole;
import com.nexusops.hrms.domain.JobRoleRepository;
import com.nexusops.hrms.domain.JobRoleSkill;
import com.nexusops.hrms.domain.JobRoleSkillRepository;
import com.nexusops.hrms.domain.Skill;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Job roles and the skills they need (D6). */
@Service
public class JobRoleService {

    static final String UNKNOWN = "Choose a job role in this workspace.";
    static final String TITLE_TAKEN = "Another job role already uses this title.";
    static final String LEVEL = "Choose a level from 1 to 5.";
    static final String REPEATED = "Each skill can be listed once.";
    private static final String LOCK = "hr-job-roles";

    private final JobRoleRepository roles;
    private final JobRoleSkillRepository requirements;
    private final DepartmentService departments;
    private final SkillService skills;
    private final TenantLocks locks;
    private final AuditService audit;

    JobRoleService(JobRoleRepository roles, JobRoleSkillRepository requirements, DepartmentService departments,
            SkillService skills, TenantLocks locks, AuditService audit) {
        this.roles = roles;
        this.requirements = requirements;
        this.departments = departments;
        this.skills = skills;
        this.locks = locks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<JobRoleView> list(boolean archived, UUID departmentId) {
        TenantContext.requireTenantId();
        List<JobRole> found = archived ? roles.findByArchivedAtIsNotNullOrderByTitleAsc()
                : roles.findByArchivedAtIsNullOrderByTitleAsc();
        return views(found.stream().filter(r -> departmentId == null || departmentId.equals(r.getDepartmentId()))
                .toList());
    }

    @Transactional(readOnly = true)
    public JobRoleView get(UUID id) {
        return views(List.of(find(id))).getFirst();
    }

    @Transactional
    public JobRoleView create(JobRoleCommand command) {
        TenantContext.requireTenantId();
        Draft d = validate(command, null);
        locks.lock(LOCK);
        if (roles.existsByTitleKey(HrRecords.key(d.title()))) {
            throw ApiProblem.conflictField("title", TITLE_TAKEN);
        }
        JobRole role = roles.saveAndFlush(new JobRole(Ids.newId(), d.title(), HrRecords.key(d.title()),
                d.departmentId(), d.description()));
        replaceRequirements(role.getId(), d.required());
        audit.record(AuditEntry.of("JobRoleCreated", "JobRole", role.getId()).withAfter(snapshot(role, d.required())));
        return views(List.of(role)).getFirst();
    }

    @Transactional
    public JobRoleView update(UUID id, JobRoleCommand command, Long version) {
        JobRole role = find(id);
        HrRecords.checkVersion(role.getVersion(), version);
        Draft d = validate(command, role);
        HrRecords.requireNotArchived(role.isArchived());
        locks.lock(LOCK);
        if (roles.existsByTitleKeyAndIdNot(HrRecords.key(d.title()), id)) {
            throw ApiProblem.conflictField("title", TITLE_TAKEN);
        }
        Map<String, Object> before = snapshot(role, requiredLevels(id));
        role.apply(d.title(), HrRecords.key(d.title()), d.departmentId(), d.description());
        roles.flush();
        replaceRequirements(id, d.required());
        audit.record(AuditEntry.of("JobRoleUpdated", "JobRole", id).withBefore(before)
                .withAfter(snapshot(role, d.required())));
        return views(List.of(role)).getFirst();
    }

    @Transactional
    public JobRoleView archive(UUID id) {
        JobRole role = find(id);
        if (!role.isArchived()) {
            role.archive(Instant.now());
            roles.flush();
            audit.record(AuditEntry.of("JobRoleArchived", "JobRole", id).withBefore(Map.of("title", role.getTitle())));
        }
        return views(List.of(role)).getFirst();
    }

    @Transactional
    public JobRoleView restore(UUID id) {
        JobRole role = find(id);
        if (role.isArchived()) {
            role.restore();
            roles.flush();
            audit.record(AuditEntry.of("JobRoleRestored", "JobRole", id).withAfter(Map.of("title", role.getTitle())));
        }
        return views(List.of(role)).getFirst();
    }

    JobRole resolve(UUID id, String field) {
        if (id == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN);
        }
        return roles.findById(id).orElseThrow(() -> ApiProblem.badRequestField(field, UNKNOWN));
    }

    Map<UUID, JobRole> byIds(Collection<UUID> ids) {
        return roles.findAllById(ids).stream().collect(Collectors.toMap(JobRole::getId, Function.identity()));
    }

    static JobRoleRef ref(JobRole role) {
        return role == null ? null : new JobRoleRef(role.getId(), role.getTitle());
    }

    /** Skill id → minimum level, in insertion order. */
    Map<UUID, Integer> requiredLevels(UUID jobRoleId) {
        Map<UUID, Integer> levels = new LinkedHashMap<>();
        requirements.findByJobRoleId(jobRoleId).forEach(r -> levels.put(r.getSkillId(), r.getMinimumLevel()));
        return levels;
    }

    private record Draft(String title, UUID departmentId, String description, Map<UUID, Integer> required) {}

    /** Body 400s first (title, description, skill levels, unknown references), then archived references (409). */
    private Draft validate(JobRoleCommand c, JobRole current) {
        String title = HrRecords.collapse(Text.required(c.title(), 80, "title"));
        String description = Text.optional(c.description(), 2000, "description");
        Map<UUID, Integer> required = new LinkedHashMap<>();
        List<RequiredSkill> raw = c.requiredSkills() == null ? List.of() : c.requiredSkills();
        for (int i = 0; i < raw.size(); i++) {
            RequiredSkill r = raw.get(i);
            if (r == null || r.skillId() == null) {
                throw ApiProblem.badRequestField("requiredSkills[" + i + "].skillId", SkillService.UNKNOWN);
            }
            if (r.minimumLevel() == null || r.minimumLevel() < 1 || r.minimumLevel() > 5) {
                throw ApiProblem.badRequestField("requiredSkills[" + i + "].minimumLevel", LEVEL);
            }
            if (required.containsKey(r.skillId())) {
                throw ApiProblem.badRequestField("requiredSkills[" + i + "].skillId", REPEATED);
            }
            required.put(r.skillId(), r.minimumLevel());
        }
        Department department = c.departmentId() == null ? null : departments.resolve(c.departmentId(), "departmentId");
        List<UUID> skillIds = new ArrayList<>(required.keySet());
        Map<UUID, Skill> found = skills.byIds(skillIds);
        for (int i = 0; i < skillIds.size(); i++) {
            if (!found.containsKey(skillIds.get(i))) {
                throw ApiProblem.badRequestField("requiredSkills[" + i + "].skillId", SkillService.UNKNOWN);
            }
        }
        if (department != null && department.isArchived()
                && (current == null || !department.getId().equals(current.getDepartmentId()))) {
            HrRecords.requireNotArchived(true);
        }
        Set<UUID> kept = current == null ? Set.of() : new HashSet<>(requiredLevels(current.getId()).keySet());
        for (UUID skillId : skillIds) {
            if (found.get(skillId).isArchived() && !kept.contains(skillId)) {
                HrRecords.requireNotArchived(true);
            }
        }
        return new Draft(title, department == null ? null : department.getId(), description, required);
    }

    private void replaceRequirements(UUID jobRoleId, Map<UUID, Integer> required) {
        requirements.deleteByJobRole(jobRoleId);
        requirements.flush();
        required.forEach((skillId, level) -> requirements.save(new JobRoleSkill(Ids.newId(), jobRoleId, skillId, level)));
        requirements.flush();
    }

    private List<JobRoleView> views(List<JobRole> list) {
        Map<UUID, List<JobRoleSkill>> byRole = requirements.findByJobRoleIdIn(list.stream().map(JobRole::getId).toList())
                .stream().collect(Collectors.groupingBy(JobRoleSkill::getJobRoleId));
        Map<UUID, Skill> skillNames = skills.byIds(byRole.values().stream().flatMap(List::stream)
                .map(JobRoleSkill::getSkillId).collect(Collectors.toSet()));
        Map<UUID, Department> depts = departments.byIds(list.stream().map(JobRole::getDepartmentId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return list.stream().map(r -> new JobRoleView(r.getId(), r.getTitle(),
                DepartmentService.ref(r.getDepartmentId() == null ? null : depts.get(r.getDepartmentId())),
                r.getDescription(),
                byRole.getOrDefault(r.getId(), List.of()).stream()
                        .map(s -> new RequiredSkillView(s.getSkillId(), skillNames.get(s.getSkillId()).getName(),
                                s.getMinimumLevel()))
                        .sorted(Comparator.comparing(RequiredSkillView::name, String.CASE_INSENSITIVE_ORDER))
                        .toList(),
                r.getArchivedAt(), r.getVersion())).toList();
    }

    private JobRole find(UUID id) {
        TenantContext.requireTenantId();
        return roles.findById(id).orElseThrow(() -> ApiProblem.notFound(HrRecords.NOT_FOUND));
    }

    private static Map<String, Object> snapshot(JobRole r, Map<UUID, Integer> required) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("title", r.getTitle());
        values.put("departmentId", r.getDepartmentId() == null ? null : r.getDepartmentId().toString());
        values.put("description", r.getDescription());
        values.put("requiredSkills", required.entrySet().stream()
                .map(e -> e.getKey() + ":" + e.getValue()).toList());
        return values;
    }
}
