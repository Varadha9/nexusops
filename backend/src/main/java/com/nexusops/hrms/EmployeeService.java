package com.nexusops.hrms;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyKind;
import com.nexusops.directory.PartyService;
import com.nexusops.directory.PartyView;
import com.nexusops.directory.PersonCommand;
import com.nexusops.hrms.domain.Department;
import com.nexusops.hrms.domain.Employee;
import com.nexusops.hrms.domain.EmployeeRepository;
import com.nexusops.hrms.domain.EmployeeSkill;
import com.nexusops.hrms.domain.EmployeeSkillRepository;
import com.nexusops.hrms.domain.EmploymentDates;
import com.nexusops.hrms.domain.JobRole;
import com.nexusops.hrms.domain.Skill;
import com.nexusops.identity.Members;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Employees (D2–D4, D7, D8): the employment record of a directory person, kept in step with the directory. */
@Service
public class EmployeeService {

    static final String UNKNOWN = "Choose an employee in this workspace.";
    static final String NOT_A_PERSON = "Choose a person in this workspace.";
    static final String ALREADY_EMPLOYED = "This person already has an employee record.";
    static final String NUMBER_TAKEN = "Another employee already has this number.";
    static final String NOT_A_MEMBER = "Choose an active team member.";
    static final String MEMBER_TAKEN = "This team member is already linked to an employee.";
    static final String LOOP = "A manager can't report to their own report.";
    static final String ENDED = "This employment has already ended.";
    static final String NOT_ENDED = "This employment hasn't ended.";
    static final String END_BEFORE_START = "The end date can't be before the start date.";
    static final String LEVEL = "Choose a level from 1 to 5.";
    static final String REPEATED = "Each skill can be listed once.";
    /**
     * Serializes hires, edits and department archiving, so loops and archived references can't slip through.
     *
     * <p>Lock order, never taken in reverse: {@code hr-employees} → {@code hr-leave-<employee>} →
     * {@code hr-training-<employee>} → {@code directory-identity} → {@code directory-roles}. The per-employee
     * locks ({@link #leaveLock}, {@link #trainingLock}) serialize ending employment against leave requests,
     * adjustments, training assignments, completions and skill edits for that employee.
     */
    static final String LOCK = "hr-employees";

    private final EmployeeRepository employees;
    private final EmployeeSkillRepository employeeSkills;
    private final PartyService parties;
    private final DepartmentService departments;
    private final JobRoleService jobRoles;
    private final SkillService skills;
    private final Members members;
    private final HrQueries queries;
    private final HrClock clock;
    private final TenantLocks locks;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final List<EmployeeHireHook> hireHooks;
    private final List<OnboardingReader> onboardingReaders;
    private final List<CourseFinder> courseFinders;

    EmployeeService(EmployeeRepository employees, EmployeeSkillRepository employeeSkills, PartyService parties,
            DepartmentService departments, JobRoleService jobRoles, SkillService skills, Members members,
            HrQueries queries, HrClock clock, TenantLocks locks, AuditService audit, ApplicationEventPublisher events,
            List<EmployeeHireHook> hireHooks, List<OnboardingReader> onboardingReaders,
            List<CourseFinder> courseFinders) {
        this.employees = employees;
        this.employeeSkills = employeeSkills;
        this.parties = parties;
        this.departments = departments;
        this.jobRoles = jobRoles;
        this.skills = skills;
        this.members = members;
        this.queries = queries;
        this.clock = clock;
        this.locks = locks;
        this.audit = audit;
        this.events = events;
        this.hireHooks = hireHooks;
        this.onboardingReaders = onboardingReaders;
        this.courseFinders = courseFinders;
    }

    @Transactional(readOnly = true)
    public PageResponse<EmployeeSummary> list(EmployeeQuery query, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Pageable paging = Paging.of(page, size);
        HrQueries.Page found = queries.employeePage(query, clock.today(), paging.getPageSize(), paging.getOffset());
        Map<UUID, Employee> byId = byIds(found.ids());
        List<Employee> ordered = found.ids().stream().map(byId::get).filter(Objects::nonNull).toList();
        return new PageResponse<>(summaries(ordered), paging.getPageNumber(), paging.getPageSize(), found.total());
    }

    @Transactional(readOnly = true)
    public EmployeeView get(UUID id) {
        return view(find(id));
    }

    @Transactional
    public EmployeeView hire(EmployeeCommand c) {
        TenantContext.requireTenantId();
        String number = number(c.employeeNumber());
        EmploymentType type = c.employmentType() == null ? EmploymentType.FULL_TIME : c.employmentType();
        LocalDate start = required(c.startDate(), "startDate", "Enter a start date.");
        if ((c.personId() == null) == (c.person() == null)) {
            throw ApiProblem.badRequestField("personId", NOT_A_PERSON);
        }
        if (c.person() != null) {
            Text.required(c.person().firstName(), 80, "person.firstName");
            Text.required(c.person().lastName(), 80, "person.lastName");
        }
        locks.lock(LOCK);
        References refs = references(c.departmentId(), c.jobRoleId(), c.managerId(), c.userId(), null);
        hireHooks.forEach(h -> h.validate(c));
        PartyBrief existing = null;
        if (c.personId() != null) {
            existing = parties.briefs(List.of(c.personId())).get(c.personId());
            if (existing == null || existing.kind() != PartyKind.PERSON) {
                throw ApiProblem.badRequestField("personId", NOT_A_PERSON);
            }
            HrRecords.requireNotArchived(existing.archived());
        }
        refs.requireNotArchived(null);
        if (existing != null && employees.existsByPersonId(existing.id())) {
            throw ApiProblem.conflictField("personId", ALREADY_EMPLOYED);
        }
        if (employees.existsByNumberKey(HrRecords.key(number))) {
            throw ApiProblem.conflictField("employeeNumber", NUMBER_TAKEN);
        }
        if (c.userId() != null && employees.existsByUserId(c.userId())) {
            throw ApiProblem.conflictField("userId", MEMBER_TAKEN);
        }
        UUID personId = existing != null ? existing.id() : parties.createPerson(new PersonCommand(
                c.person().firstName(), c.person().lastName(), null, null, c.person().email(), c.person().phone(),
                null)).id();
        Employee employee = new Employee(Ids.newId(), personId);
        employee.apply(number, HrRecords.key(number), refs.departmentId(), refs.jobRoleId(), refs.managerId(), type,
                start, c.userId());
        employees.saveAndFlush(employee);
        parties.recordEmployment(personId, number, start, true);
        audit.record(AuditEntry.of("EmployeeHired", "Employee", employee.getId()).withAfter(snapshot(employee)));
        hireHooks.forEach(h -> h.afterHire(employee, c));
        return view(employee);
    }

    @Transactional
    public EmployeeView update(UUID id, EmployeeUpdate c, Long version) {
        Employee employee = find(id);
        HrRecords.checkVersion(employee.getVersion(), version);
        String number = number(c.employeeNumber());
        EmploymentType type = c.employmentType() == null ? employee.getEmploymentType() : c.employmentType();
        LocalDate start = required(c.startDate(), "startDate", "Enter a start date.");
        if (employee.getEndDate() != null && employee.getEndDate().isBefore(start)) {
            throw ApiProblem.badRequestField("startDate", END_BEFORE_START);
        }
        locks.lock(LOCK);
        References refs = references(c.departmentId(), c.jobRoleId(), c.managerId(), c.userId(), employee);
        refs.requireNotArchived(employee);
        if (employees.existsByNumberKeyAndIdNot(HrRecords.key(number), id)) {
            throw ApiProblem.conflictField("employeeNumber", NUMBER_TAKEN);
        }
        if (c.userId() != null && employees.existsByUserIdAndIdNot(c.userId(), id)) {
            throw ApiProblem.conflictField("userId", MEMBER_TAKEN);
        }
        Map<String, Object> before = snapshot(employee);
        employee.apply(number, HrRecords.key(number), refs.departmentId(), refs.jobRoleId(), refs.managerId(), type,
                start, c.userId());
        employees.flush();
        parties.recordEmployment(employee.getPersonId(), number, start, employee.getEndDate() == null);
        audit.record(AuditEntry.of("EmployeeUpdated", "Employee", id).withBefore(before).withAfter(snapshot(employee)));
        return view(employee);
    }

    @Transactional
    public EmployeeView end(UUID id, EndEmployment c, Long version) {
        Employee employee = find(id);
        HrRecords.checkVersion(employee.getVersion(), version);
        LocalDate date = required(c.endDate(), "endDate", "Enter the last day of employment.");
        String reason = Text.optional(c.reason(), 500, "reason");
        if (date.isBefore(employee.getStartDate())) {
            throw ApiProblem.badRequestField("endDate", END_BEFORE_START);
        }
        // I-1: queue behind (and block) leave requests and training assignments for this employee, so they
        // either commit before the end (and are cancelled below) or re-read the end date after it.
        locks.lock(leaveLock(id));
        locks.lock(trainingLock(id));
        if (employee.getEndDate() != null) {
            throw ApiProblem.conflict(ENDED);
        }
        employee.end(date, reason);
        employees.flush();
        parties.recordEmployment(employee.getPersonId(), employee.getEmployeeNumber(), employee.getStartDate(), false);
        events.publishEvent(new EmploymentEndedEvent(id, date));
        audit.record(AuditEntry.of("EmploymentEnded", "Employee", id).withAfter(Map.of("endDate", date.toString(),
                "reason", reason == null ? "" : reason)));
        return view(employee);
    }

    @Transactional
    public EmployeeView reinstate(UUID id, Long version) {
        Employee employee = find(id);
        HrRecords.checkVersion(employee.getVersion(), version);
        if (employee.getEndDate() == null) {
            throw ApiProblem.conflict(NOT_ENDED);
        }
        LocalDate ended = employee.getEndDate();
        employee.reinstate();
        employees.flush();
        parties.recordEmployment(employee.getPersonId(), employee.getEmployeeNumber(), employee.getStartDate(), true);
        audit.record(AuditEntry.of("EmploymentReinstated", "Employee", id).withBefore(Map.of("endDate",
                ended.toString())));
        return view(employee);
    }

    @Transactional
    public EmployeeView setSkills(UUID id, List<EmployeeSkillCommand> raw, Long version) {
        Employee employee = find(id);
        HrRecords.checkVersion(employee.getVersion(), version);
        List<EmployeeSkillCommand> list = raw == null ? List.of() : raw;
        Map<UUID, EmployeeSkillCommand> wanted = new LinkedHashMap<>();
        for (int i = 0; i < list.size(); i++) {
            EmployeeSkillCommand s = list.get(i);
            if (s == null || s.skillId() == null) {
                throw ApiProblem.badRequestField("skills[" + i + "].skillId", SkillService.UNKNOWN);
            }
            if (s.level() == null || s.level() < 1 || s.level() > 5) {
                throw ApiProblem.badRequestField("skills[" + i + "].level", LEVEL);
            }
            Text.optional(s.note(), 200, "skills[" + i + "].note");
            if (wanted.containsKey(s.skillId())) {
                throw ApiProblem.badRequestField("skills[" + i + "].skillId", REPEATED);
            }
            wanted.put(s.skillId(), s);
        }
        List<UUID> ids = new ArrayList<>(wanted.keySet());
        Map<UUID, Skill> found = skills.byIds(ids);
        for (int i = 0; i < ids.size(); i++) {
            if (!found.containsKey(ids.get(i))) {
                throw ApiProblem.badRequestField("skills[" + i + "].skillId", SkillService.UNKNOWN);
            }
        }
        Set<UUID> held = heldLevels(id).keySet();
        for (UUID skillId : ids) {
            if (found.get(skillId).isArchived() && !held.contains(skillId)) {
                HrRecords.requireNotArchived(true);
            }
        }
        Map<UUID, Integer> before = heldLevels(id);
        employeeSkills.deleteByEmployee(id);
        employeeSkills.flush();
        wanted.values().forEach(s -> employeeSkills.save(new EmployeeSkill(Ids.newId(), id, s.skillId(), s.level(),
                Text.optional(s.note(), 200, "note"))));
        employeeSkills.flush();
        employee.touch();
        employees.flush();
        audit.record(AuditEntry.of("EmployeeSkillsUpdated", "Employee", id).withBefore(levels(before))
                .withAfter(levels(heldLevels(id))));
        return view(employee);
    }

    @Transactional(readOnly = true)
    public SkillGapView skillGap(UUID id) {
        Employee employee = find(id);
        EmployeeRef ref = ref(employee, names(List.of(employee)));
        if (employee.getJobRoleId() == null) {
            return new SkillGapView(ref, null, List.of());
        }
        JobRole role = jobRoles.byIds(List.of(employee.getJobRoleId())).get(employee.getJobRoleId());
        List<SkillGaps.Gap> gaps = SkillGaps.gaps(jobRoles.requiredLevels(role.getId()), heldLevels(id));
        Map<UUID, Skill> names = skills.byIds(gaps.stream().map(SkillGaps.Gap::skillId).toList());
        return new SkillGapView(ref, JobRoleService.ref(role), gaps.stream()
                .map(g -> new SkillGapView.Gap(SkillService.ref(names.get(g.skillId())), g.required(), g.held(),
                        courseFinders.stream().flatMap(f -> f.teaching(g.skillId(), g.required()).stream()).toList()))
                .toList());
    }

    /** Active team members an employee record can be linked to (D2), first 20 matches. */
    @Transactional(readOnly = true)
    public List<com.nexusops.collaboration.AssigneeView> members(String q) {
        return members.searchActive(q, 20).stream()
                .map(m -> new com.nexusops.collaboration.AssigneeView(m.id(), m.name(), m.email())).toList();
    }

    // ---- for other HRMS services ----

    Employee find(UUID id) {
        TenantContext.requireTenantId();
        return employees.findById(id).orElseThrow(() -> ApiProblem.notFound(HrRecords.NOT_FOUND));
    }

    static String leaveLock(UUID employeeId) {
        return "hr-leave-" + employeeId;
    }

    static String trainingLock(UUID employeeId) {
        return "hr-training-" + employeeId;
    }

    /** The employment dates as committed now; call it under a per-employee lock to re-check them (I-1). */
    EmploymentDates currentDates(UUID id) {
        return employees.employmentDates(id).orElseThrow(() -> ApiProblem.notFound(HrRecords.NOT_FOUND));
    }

    Employee resolve(UUID id, String field) {
        if (id == null) {
            throw ApiProblem.badRequestField(field, UNKNOWN);
        }
        return employees.findById(id).orElseThrow(() -> ApiProblem.badRequestField(field, UNKNOWN));
    }

    Map<UUID, Employee> byIds(Collection<UUID> ids) {
        return employees.findAllById(ids).stream().collect(Collectors.toMap(Employee::getId, Function.identity()));
    }

    Optional<Employee> forUser(UUID userId) {
        return userId == null ? Optional.empty() : employees.findByUserId(userId);
    }

    /** Employee id → the person's display name. */
    Map<UUID, String> names(Collection<Employee> list) {
        Map<UUID, PartyBrief> people = parties.briefs(list.stream().map(Employee::getPersonId).collect(Collectors.toSet()));
        Map<UUID, String> names = new HashMap<>();
        list.forEach(e -> names.put(e.getId(), people.containsKey(e.getPersonId())
                ? people.get(e.getPersonId()).name() : e.getEmployeeNumber()));
        return names;
    }

    EmployeeRef ref(Employee e, Map<UUID, String> names) {
        return e == null ? null : new EmployeeRef(e.getId(), names.get(e.getId()), e.getEmployeeNumber());
    }

    Map<UUID, Integer> heldLevels(UUID employeeId) {
        Map<UUID, Integer> levels = new LinkedHashMap<>();
        employeeSkills.findByEmployeeId(employeeId).forEach(s -> levels.put(s.getSkillId(), s.getLevel()));
        return levels;
    }

    /** Task 5: completing a course raises each taught skill to the course level when lower; audited. */
    void raiseSkills(UUID employeeId, Map<UUID, Integer> levels) {
        Map<UUID, Integer> before = heldLevels(employeeId);
        levels.forEach((skillId, level) -> employeeSkills.findByEmployeeIdAndSkillId(employeeId, skillId).ifPresentOrElse(
                s -> s.raise(level),
                () -> employeeSkills.save(new EmployeeSkill(Ids.newId(), employeeId, skillId, level, null))));
        employeeSkills.flush();
        Map<UUID, Integer> after = heldLevels(employeeId);
        if (!after.equals(before)) {
            find(employeeId).touch();
            employees.flush();
            audit.record(AuditEntry.of("EmployeeSkillsUpdated", "Employee", employeeId).withBefore(levels(before))
                    .withAfter(levels(after)));
        }
    }

    // ---- validation ----

    private record References(UUID departmentId, Department department, UUID jobRoleId, JobRole jobRole,
            UUID managerId) {

        /** Archived department or job role newly chosen → 409 (an unchanged one stays). */
        void requireNotArchived(Employee current) {
            if (department != null && department.isArchived()
                    && (current == null || !department.getId().equals(current.getDepartmentId()))) {
                HrRecords.requireNotArchived(true);
            }
            if (jobRole != null && jobRole.isArchived()
                    && (current == null || !jobRole.getId().equals(current.getJobRoleId()))) {
                HrRecords.requireNotArchived(true);
            }
        }
    }

    /** 400s for every referenced id, including the manager loop. */
    private References references(UUID departmentId, UUID jobRoleId, UUID managerId, UUID userId, Employee current) {
        Department department = departmentId == null ? null : departments.resolve(departmentId, "departmentId");
        JobRole jobRole = jobRoleId == null ? null : jobRoles.resolve(jobRoleId, "jobRoleId");
        if (managerId != null) {
            if (current != null && managerId.equals(current.getId())) {
                throw ApiProblem.badRequestField("managerId", LOOP);
            }
            resolve(managerId, "managerId");
            if (current != null) {
                Map<UUID, UUID> managerOf = new HashMap<>();
                employees.managerPairs().forEach(p -> managerOf.put((UUID) p[0], (UUID) p[1]));
                if (ManagerChain.createsLoop(current.getId(), managerId, managerOf)) {
                    throw ApiProblem.badRequestField("managerId", LOOP);
                }
            }
        }
        if (userId != null && (current == null || !userId.equals(current.getUserId()))
                && members.findActive(userId).isEmpty()) {
            throw ApiProblem.badRequestField("userId", NOT_A_MEMBER);
        }
        return new References(departmentId, department, jobRoleId, jobRole, managerId);
    }

    private static String number(String raw) {
        return Text.required(raw, 40, "employeeNumber");
    }

    private static LocalDate required(LocalDate value, String field, String message) {
        if (value == null) {
            throw ApiProblem.badRequestField(field, message);
        }
        return value;
    }

    // ---- views ----

    private EmployeeView view(Employee e) {
        PartyView person = parties.get(e.getPersonId());
        Map<UUID, Employee> managers = e.getManagerId() == null ? Map.of() : byIds(List.of(e.getManagerId()));
        Employee manager = e.getManagerId() == null ? null : managers.get(e.getManagerId());
        Members.Member user = e.getUserId() == null ? null : members.findAll(List.of(e.getUserId())).get(e.getUserId());
        List<EmployeeSkill> held = employeeSkills.findByEmployeeId(e.getId());
        Map<UUID, Skill> skillNames = skills.byIds(held.stream().map(EmployeeSkill::getSkillId).toList());
        Department department = e.getDepartmentId() == null ? null
                : departments.byIds(List.of(e.getDepartmentId())).get(e.getDepartmentId());
        JobRole jobRole = e.getJobRoleId() == null ? null : jobRoles.byIds(List.of(e.getJobRoleId())).get(e.getJobRoleId());
        OnboardingProgress onboarding = onboardingReaders.stream().map(r -> r.progress(e.getId()))
                .filter(Objects::nonNull).findFirst().orElse(null);
        return new EmployeeView(e.getId(), new PersonRef(person.id(), person.name(), person.email(), person.phone()),
                e.getEmployeeNumber(), DepartmentService.ref(department), JobRoleService.ref(jobRole),
                manager == null ? null : ref(manager, names(List.of(manager))), e.getEmploymentType(), e.getStartDate(),
                e.getEndDate(), e.getEndReason(), EmploymentStatus.of(e.getStartDate(), e.getEndDate(), clock.today()),
                user == null ? null : new MemberRef(user.id(), user.name()),
                held.stream().map(s -> new EmployeeSkillView(s.getSkillId(), skillNames.get(s.getSkillId()).getName(),
                        s.getLevel(), s.getNote()))
                        .sorted(Comparator.comparing(EmployeeSkillView::name, String.CASE_INSENSITIVE_ORDER)).toList(),
                onboarding, e.getCreatedAt(), e.getUpdatedAt(), e.getVersion());
    }

    List<EmployeeSummary> summaries(List<Employee> list) {
        Set<UUID> managerIds = list.stream().map(Employee::getManagerId).filter(Objects::nonNull).collect(Collectors.toSet());
        Map<UUID, Employee> managers = byIds(managerIds);
        Map<UUID, String> names = names(Stream.concat(list.stream(), managers.values().stream()).toList());
        Map<UUID, Department> depts = departments.byIds(list.stream().map(Employee::getDepartmentId)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        Map<UUID, JobRole> roles = jobRoles.byIds(list.stream().map(Employee::getJobRoleId).filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        LocalDate today = clock.today();
        return list.stream().map(e -> new EmployeeSummary(e.getId(), names.get(e.getId()), e.getEmployeeNumber(),
                DepartmentService.ref(e.getDepartmentId() == null ? null : depts.get(e.getDepartmentId())),
                JobRoleService.ref(e.getJobRoleId() == null ? null : roles.get(e.getJobRoleId())),
                e.getManagerId() == null ? null : ref(managers.get(e.getManagerId()), names),
                EmploymentStatus.of(e.getStartDate(), e.getEndDate(), today), e.getStartDate(), e.getEndDate()))
                .toList();
    }

    private static Map<String, Object> levels(Map<UUID, Integer> levels) {
        Map<String, Object> values = new LinkedHashMap<>();
        levels.forEach((k, v) -> values.put(k.toString(), v));
        return values;
    }

    private static Map<String, Object> snapshot(Employee e) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("employeeNumber", e.getEmployeeNumber());
        values.put("departmentId", e.getDepartmentId() == null ? null : e.getDepartmentId().toString());
        values.put("jobRoleId", e.getJobRoleId() == null ? null : e.getJobRoleId().toString());
        values.put("managerId", e.getManagerId() == null ? null : e.getManagerId().toString());
        values.put("employmentType", e.getEmploymentType().name());
        values.put("startDate", e.getStartDate().toString());
        values.put("userId", e.getUserId() == null ? null : e.getUserId().toString());
        return values;
    }
}
