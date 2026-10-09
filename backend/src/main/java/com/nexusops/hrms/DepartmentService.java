package com.nexusops.hrms;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.hrms.domain.Department;
import com.nexusops.hrms.domain.DepartmentRepository;
import com.nexusops.hrms.domain.EmployeeRepository;
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

/** Departments (D5): unique names ignoring case, an optional description, archive/restore. */
@Service
public class DepartmentService {

    static final String UNKNOWN = "Choose a department in this workspace.";
    static final String NAME_TAKEN = "Another department already uses this name.";
    static final String EMPLOYEES_LEFT = "Move this department's employees first.";
    private static final String LOCK = "hr-departments";

    private final DepartmentRepository departments;
    private final EmployeeRepository employees;
    private final HrClock clock;
    private final TenantLocks locks;
    private final AuditService audit;

    DepartmentService(DepartmentRepository departments, EmployeeRepository employees, HrClock clock, TenantLocks locks,
            AuditService audit) {
        this.departments = departments;
        this.employees = employees;
        this.clock = clock;
        this.locks = locks;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<DepartmentView> list(boolean archived) {
        TenantContext.requireTenantId();
        return (archived ? departments.findByArchivedAtIsNotNullOrderByNameAsc()
                : departments.findByArchivedAtIsNullOrderByNameAsc()).stream().map(DepartmentService::view).toList();
    }

    @Transactional
    public DepartmentView create(DepartmentCommand command) {
        TenantContext.requireTenantId();
        String name = name(command.name());
        String description = Text.optional(command.description(), 500, "description");
        locks.lock(LOCK);
        if (departments.existsByNameKey(HrRecords.key(name))) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        Department department = departments
                .saveAndFlush(new Department(Ids.newId(), name, HrRecords.key(name), description));
        audit.record(AuditEntry.of("DepartmentCreated", "Department", department.getId())
                .withAfter(snapshot(department)));
        return view(department);
    }

    @Transactional
    public DepartmentView update(UUID id, DepartmentCommand command, Long version) {
        Department department = find(id);
        HrRecords.checkVersion(department.getVersion(), version);
        String name = name(command.name());
        String description = Text.optional(command.description(), 500, "description");
        HrRecords.requireNotArchived(department.isArchived());
        locks.lock(LOCK);
        if (departments.existsByNameKeyAndIdNot(HrRecords.key(name), id)) {
            throw ApiProblem.conflictField("name", NAME_TAKEN);
        }
        Map<String, Object> before = snapshot(department);
        department.apply(name, HrRecords.key(name), description);
        departments.flush();
        audit.record(AuditEntry.of("DepartmentUpdated", "Department", id).withBefore(before)
                .withAfter(snapshot(department)));
        return view(department);
    }

    @Transactional
    public DepartmentView archive(UUID id) {
        Department department = find(id);
        if (!department.isArchived()) {
            locks.lock(EmployeeService.LOCK);
            if (employees.anyCurrentIn(id, clock.today())) {
                throw ApiProblem.conflict(EMPLOYEES_LEFT);
            }
            department.archive(Instant.now());
            departments.flush();
            audit.record(AuditEntry.of("DepartmentArchived", "Department", id).withBefore(snapshot(department)));
        }
        return view(department);
    }

    @Transactional
    public DepartmentView restore(UUID id) {
        Department department = find(id);
        if (department.isArchived()) {
            department.restore();
            departments.flush();
            audit.record(AuditEntry.of("DepartmentRestored", "Department", id).withAfter(snapshot(department)));
        }
        return view(department);
    }

    /** A department referenced from a request body: unknown or other-tenant → 400 on {@code field}. */
    Department resolve(UUID id, String field) {
        if (id == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN);
        }
        return departments.findById(id).orElseThrow(() -> ApiProblem.badRequestField(field, UNKNOWN));
    }

    Map<UUID, Department> byIds(Collection<UUID> ids) {
        return departments.findAllById(ids).stream().collect(Collectors.toMap(Department::getId, Function.identity()));
    }

    static DepartmentRef ref(Department department) {
        return department == null ? null : new DepartmentRef(department.getId(), department.getName());
    }

    private Department find(UUID id) {
        TenantContext.requireTenantId();
        return departments.findById(id).orElseThrow(() -> ApiProblem.notFound(HrRecords.NOT_FOUND));
    }

    private static String name(String raw) {
        return HrRecords.collapse(Text.required(raw, 80, "name"));
    }

    private static Map<String, Object> snapshot(Department d) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", d.getName());
        values.put("description", d.getDescription());
        return values;
    }

    private static DepartmentView view(Department d) {
        return new DepartmentView(d.getId(), d.getName(), d.getDescription(), d.getArchivedAt(), d.getVersion());
    }
}
