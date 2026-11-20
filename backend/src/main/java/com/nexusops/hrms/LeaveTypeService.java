package com.nexusops.hrms;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.hrms.domain.LeaveType;
import com.nexusops.hrms.domain.LeaveTypeRepository;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Leave types (D11): unique names ignoring case, a paid flag, an optional yearly allowance, archive/restore. */
@Service
public class LeaveTypeService {

    static final String UNKNOWN = "Choose a leave type in this workspace.";
    static final String NAME_TAKEN = "Another leave type already uses this name.";
    static final String PAID = "Say whether this leave is paid.";
    static final String ALLOWANCE = "Use 0 to 366 days, or leave it empty for no limit.";
    private static final String LOCK = "hr-leave-types";

    private final LeaveTypeRepository types;
    private final TenantLocks locks;
    private final AuditService audit;

    LeaveTypeService(LeaveTypeRepository types, TenantLocks locks, AuditService audit) {
        this.types = types;
        this.locks = locks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<LeaveTypeView> list(boolean archived) {
        TenantContext.requireTenantId();
        return (archived ? types.findByArchivedAtIsNotNullOrderByPositionAscNameAsc()
                : types.findByArchivedAtIsNullOrderByPositionAscNameAsc()).stream().map(LeaveTypeService::view).toList();
    }

    @Transactional
    public LeaveTypeView create(LeaveTypeCommand command) {
        TenantContext.requireTenantId();
        Draft d = validate(command);
        locks.lock(LOCK);
        if (types.existsByNameKey(HrRecords.key(d.name()))) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        return insert(d, (int) types.count(), "LeaveTypeCreated");
    }

    @Transactional
    public LeaveTypeView update(UUID id, LeaveTypeCommand command, Long version) {
        LeaveType type = find(id);
        HrRecords.checkVersion(type.getVersion(), version);
        Draft d = validate(command);
        HrRecords.requireNotArchived(type.isArchived());
        locks.lock(LOCK);
        if (types.existsByNameKeyAndIdNot(HrRecords.key(d.name()), id)) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        Map<String, Object> before = snapshot(type.getName(), type.isPaid(), type.getAnnualAllowance());
        type.apply(d.name(), HrRecords.key(d.name()), d.paid(), d.allowance());
        types.flush();
        audit.record(AuditEntry.of("LeaveTypeUpdated", "LeaveType", id).withBefore(before)
                .withAfter(snapshot(d.name(), d.paid(), d.allowance())));
        return view(type);
    }

    @Transactional
    public LeaveTypeView archive(UUID id) {
        LeaveType type = find(id);
        if (!type.isArchived()) {
            type.archive(Instant.now());
            types.flush();
            audit.record(AuditEntry.of("LeaveTypeArchived", "LeaveType", id).withBefore(Map.of("name", type.getName())));
        }
        return view(type);
    }

    @Transactional
    public LeaveTypeView restore(UUID id) {
        LeaveType type = find(id);
        if (type.isArchived()) {
            type.restore();
            types.flush();
            audit.record(AuditEntry.of("LeaveTypeRestored", "LeaveType", id).withAfter(Map.of("name", type.getName())));
        }
        return view(type);
    }

    /** Every new workspace starts with annual, sick and unpaid leave (D11). */
    @Transactional
    public void seedDefaults() {
        TenantContext.requireTenantId();
        locks.lock(LOCK);
        if (types.count() > 0) {
            return;
        }
        insert(new Draft("Annual leave", true, 20), 0, "LeaveTypeCreated");
        insert(new Draft("Sick leave", true, 10), 1, "LeaveTypeCreated");
        insert(new Draft("Unpaid leave", false, null), 2, "LeaveTypeCreated");
    }

    LeaveType resolve(UUID id, String field) {
        if (id == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN);
        }
        return types.findById(id).orElseThrow(() -> ApiProblem.badRequestField(field, UNKNOWN));
    }

    Map<UUID, LeaveType> byIds(Collection<UUID> ids) {
        return types.findAllById(ids).stream().collect(Collectors.toMap(LeaveType::getId, Function.identity()));
    }

    List<LeaveType> active() {
        return types.findByArchivedAtIsNullOrderByPositionAscNameAsc();
    }

    static LeaveTypeRef ref(LeaveType type) {
        return type == null ? null : new LeaveTypeRef(type.getId(), type.getName());
    }

    private LeaveTypeView insert(Draft d, int position, String action) {
        LeaveType type = types.saveAndFlush(new LeaveType(Ids.newId(), d.name(), HrRecords.key(d.name()), d.paid(),
                d.allowance(), position));
        audit.record(AuditEntry.of(action, "LeaveType", type.getId()).withAfter(snapshot(d.name(), d.paid(),
                d.allowance())));
        return view(type);
    }

    private LeaveType find(UUID id) {
        TenantContext.requireTenantId();
        return types.findById(id).orElseThrow(() -> ApiProblem.notFound(HrRecords.NOT_FOUND));
    }

    private static Draft validate(LeaveTypeCommand c) {
        String name = HrRecords.collapse(Text.required(c.name(), 60, "name"));
        if (c.paid() == null) {
            throw ApiProblem.badRequestField("paid", PAID);
        }
        if (c.annualAllowance() != null && (c.annualAllowance() < 0 || c.annualAllowance() > 366)) {
            throw ApiProblem.badRequestField("annualAllowance", ALLOWANCE);
        }
        return new Draft(name, c.paid(), c.annualAllowance());
    }

    private static Map<String, Object> snapshot(String name, boolean paid, Integer allowance) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", name);
        snapshot.put("paid", paid);
        snapshot.put("annualAllowance", allowance);
        return snapshot;
    }

    private static LeaveTypeView view(LeaveType t) {
        return new LeaveTypeView(t.getId(), t.getName(), t.isPaid(), t.getAnnualAllowance(), t.getArchivedAt(),
                t.getVersion());
    }

    private record Draft(String name, boolean paid, Integer allowance) {}
}
