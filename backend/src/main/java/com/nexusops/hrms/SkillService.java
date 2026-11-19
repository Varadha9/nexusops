package com.nexusops.hrms;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.hrms.domain.Skill;
import com.nexusops.hrms.domain.SkillRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** The skills catalog (D7): unique names ignoring case, archive/restore. */
@Service
public class SkillService {

    static final String UNKNOWN = "Choose a skill in this workspace.";
    static final String NAME_TAKEN = "Another skill already uses this name.";
    private static final String LOCK = "hr-skills";

    private final SkillRepository skills;
    private final TenantLocks locks;
    private final AuditService audit;

    SkillService(SkillRepository skills, TenantLocks locks, AuditService audit) {
        this.skills = skills;
        this.locks = locks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<SkillView> list(boolean archived) {
        TenantContext.requireTenantId();
        return (archived ? skills.findByArchivedAtIsNotNullOrderByNameAsc() : skills.findByArchivedAtIsNullOrderByNameAsc())
                .stream().map(SkillService::view).toList();
    }

    @Transactional
    public SkillView create(String rawName) {
        TenantContext.requireTenantId();
        String name = name(rawName);
        locks.lock(LOCK);
        if (skills.existsByNameKey(HrRecords.key(name))) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        Skill skill = skills.saveAndFlush(new Skill(Ids.newId(), name, HrRecords.key(name)));
        audit.record(AuditEntry.of("SkillCreated", "Skill", skill.getId()).withAfter(Map.of("name", name)));
        return view(skill);
    }

    @Transactional
    public SkillView update(UUID id, String rawName, Long version) {
        Skill skill = find(id);
        HrRecords.checkVersion(skill.getVersion(), version);
        String name = name(rawName);
        HrRecords.requireNotArchived(skill.isArchived());
        locks.lock(LOCK);
        if (skills.existsByNameKeyAndIdNot(HrRecords.key(name), id)) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        String before = skill.getName();
        skill.rename(name, HrRecords.key(name));
        skills.flush();
        audit.record(AuditEntry.of("SkillUpdated", "Skill", id).withBefore(Map.of("name", before))
                .withAfter(Map.of("name", name)));
        return view(skill);
    }

    @Transactional
    public SkillView archive(UUID id) {
        Skill skill = find(id);
        if (!skill.isArchived()) {
            skill.archive(Instant.now());
            skills.flush();
            audit.record(AuditEntry.of("SkillArchived", "Skill", id).withBefore(Map.of("name", skill.getName())));
        }
        return view(skill);
    }

    @Transactional
    public SkillView restore(UUID id) {
        Skill skill = find(id);
        if (skill.isArchived()) {
            skill.restore();
            skills.flush();
            audit.record(AuditEntry.of("SkillRestored", "Skill", id).withAfter(Map.of("name", skill.getName())));
        }
        return view(skill);
    }

    Skill resolve(UUID id, String field) {
        if (id == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN);
        }
        return skills.findById(id).orElseThrow(() -> ApiProblem.badRequestField(field, UNKNOWN));
    }

    Map<UUID, Skill> byIds(Collection<UUID> ids) {
        return skills.findAllById(ids).stream().collect(Collectors.toMap(Skill::getId, Function.identity()));
    }

    static SkillRef ref(Skill skill) {
        return skill == null ? null : new SkillRef(skill.getId(), skill.getName());
    }

    private Skill find(UUID id) {
        TenantContext.requireTenantId();
        return skills.findById(id).orElseThrow(() -> ApiProblem.notFound(HrRecords.NOT_FOUND));
    }

    private static String name(String raw) {
        return HrRecords.collapse(Text.required(raw, 60, "name"));
    }

    private static SkillView view(Skill s) {
        return new SkillView(s.getId(), s.getName(), s.getArchivedAt(), s.getVersion());
    }
}
