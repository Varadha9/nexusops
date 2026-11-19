package com.nexusops.hrms;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.hrms.domain.Course;
import com.nexusops.hrms.domain.Employee;
import com.nexusops.hrms.domain.EmploymentDates;
import com.nexusops.hrms.domain.TrainingAssignment;
import com.nexusops.hrms.domain.TrainingAssignmentRepository;
import com.nexusops.identity.Members;
import com.nexusops.notifications.MailRequested;
import com.nexusops.notifications.OutgoingMail;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import com.nexusops.shared.db.TenantLocks;
import com.nexusops.shared.web.ApiProblem;
import com.nexusops.shared.web.PageResponse;
import com.nexusops.shared.web.Paging;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** D9: assigning courses to employees; completion raises the skills the course teaches. */
@Service
public class TrainingService {

    static final String ALREADY = "This course is already assigned to this employee.";
    static final String LEFT = "This employee has left.";
    static final String NOT_OPEN = "This training is no longer open.";

    private final TrainingAssignmentRepository assignments;
    private final CourseService courses;
    private final EmployeeService employees;
    private final Members members;
    private final HrClock clock;
    private final TenantLocks locks;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final String appBaseUrl;

    TrainingService(TrainingAssignmentRepository assignments, CourseService courses, EmployeeService employees,
            Members members, HrClock clock, TenantLocks locks, AuditService audit, ApplicationEventPublisher events,
            @Value("${nexusops.app.base-url}") String appBaseUrl) {
        this.assignments = assignments;
        this.courses = courses;
        this.employees = employees;
        this.members = members;
        this.clock = clock;
        this.locks = locks;
        this.audit = audit;
        this.events = events;
        this.appBaseUrl = appBaseUrl;
    }

    @Transactional(readOnly = true)
    public PageResponse<TrainingAssignmentView> list(TrainingQuery q, Integer page, Integer size) {
        TenantContext.requireTenantId();
        LocalDate today = clock.today();
        boolean overdue = Boolean.TRUE.equals(q.overdue());
        Specification<TrainingAssignment> spec = (root, cq, cb) -> cb.and(Stream.of(
                q.employeeId() == null ? null : cb.equal(root.get("employeeId"), q.employeeId()),
                q.status() == null ? null : cb.equal(root.get("status"), q.status()),
                overdue ? cb.equal(root.get("status"), TrainingStatus.ASSIGNED) : null,
                overdue ? cb.lessThan(root.get("dueOn"), today) : null)
                .filter(Objects::nonNull).toArray(jakarta.persistence.criteria.Predicate[]::new));
        Pageable paging = Paging.of(page, size, Sort.by(Sort.Order.asc("dueOn").nullsLast(), Sort.Order.asc("id")));
        Page<TrainingAssignment> found = assignments.findAll(spec, paging);
        return new PageResponse<>(views(found.getContent()), found.getNumber(), found.getSize(), found.getTotalElements());
    }

    @Transactional
    public TrainingAssignmentView assign(UUID courseId, UUID employeeId, LocalDate dueOn) {
        HrDates.check(dueOn, "dueOn");
        Course course = courses.resolve(courseId, "courseId");
        Employee employee = employees.resolve(employeeId, "employeeId");
        locks.lock(EmployeeService.trainingLock(employee.getId()));
        HrRecords.requireNotArchived(course.isArchived());
        // I-1: the employment may have ended while we waited; check against the committed dates.
        EmploymentDates now = employees.currentDates(employee.getId());
        if (EmploymentStatus.of(now.startDate(), now.endDate(), clock.today()) == EmploymentStatus.LEFT) {
            throw ApiProblem.conflict(LEFT);
        }
        if (assignments.existsByCourseIdAndEmployeeIdAndStatus(courseId, employeeId, TrainingStatus.ASSIGNED)) {
            throw ApiProblem.conflict(ALREADY);
        }
        TrainingAssignment a = assignments.saveAndFlush(new TrainingAssignment(Ids.newId(), courseId, employeeId, dueOn,
                TenantContext.userId().orElse(null)));
        audit.record(AuditEntry.of("TrainingAssigned", "TrainingAssignment", a.getId())
                .withAfter(Map.of("courseId", courseId.toString(), "employeeId", employeeId.toString())));
        notifyEmployee(employee, course, dueOn);
        return views(List.of(a)).getFirst();
    }

    @Transactional
    public TrainingAssignmentView complete(UUID id, String note, Long version) {
        return doComplete(find(id), note, version);
    }

    @Transactional
    TrainingAssignmentView completeOwn(Employee employee, UUID id, String note, Long version) {
        TrainingAssignment a = find(id);
        if (!a.getEmployeeId().equals(employee.getId())) {
            throw ApiProblem.notFound(HrRecords.NOT_FOUND);
        }
        return doComplete(a, note, version);
    }

    @Transactional
    public TrainingAssignmentView cancel(UUID id, Long version) {
        TrainingAssignment a = find(id);
        HrRecords.checkVersion(a.getVersion(), version);
        if (a.getStatus() != TrainingStatus.ASSIGNED) {
            throw ApiProblem.conflict(NOT_OPEN);
        }
        a.cancel();
        assignments.flush();
        audit.record(AuditEntry.of("TrainingCancelled", "TrainingAssignment", id));
        return views(List.of(a)).getFirst();
    }

    List<TrainingAssignmentView> openFor(UUID employeeId) {
        return views(assignments.findByEmployeeIdAndStatusOrderByDueOnAsc(employeeId, TrainingStatus.ASSIGNED));
    }

    long overdueCount() {
        return assignments.overdue(clock.today());
    }

    /** D4: open trainings end with the employment. */
    @EventListener
    void on(EmploymentEndedEvent event) {
        for (TrainingAssignment a : assignments.findByEmployeeIdAndStatusOrderByDueOnAsc(event.employeeId(),
                TrainingStatus.ASSIGNED)) {
            a.cancel();
            audit.record(AuditEntry.of("TrainingCancelled", "TrainingAssignment", a.getId())
                    .withAfter(Map.of("reason", "Employment ended")));
        }
        assignments.flush();
    }

    private TrainingAssignmentView doComplete(TrainingAssignment a, String rawNote, Long version) {
        locks.lock(EmployeeService.trainingLock(a.getEmployeeId()));
        HrRecords.checkVersion(a.getVersion(), version);
        String note = Text.optional(rawNote, 500, "note");
        if (a.getStatus() != TrainingStatus.ASSIGNED) {
            throw ApiProblem.conflict(NOT_OPEN);
        }
        a.complete(clock.today(), note);
        assignments.flush();
        employees.raiseSkills(a.getEmployeeId(), courses.taught(a.getCourseId()));
        audit.record(AuditEntry.of("TrainingCompleted", "TrainingAssignment", a.getId())
                .withAfter(note == null ? Map.of("courseId", a.getCourseId().toString())
                        : Map.of("courseId", a.getCourseId().toString(), "note", note)));
        return views(List.of(a)).getFirst();
    }

    private TrainingAssignment find(UUID id) {
        TenantContext.requireTenantId();
        return assignments.findById(id).orElseThrow(() -> ApiProblem.notFound(HrRecords.NOT_FOUND));
    }

    private void notifyEmployee(Employee employee, Course course, LocalDate dueOn) {
        if (employee.getUserId() == null || employee.getUserId().equals(TenantContext.userId().orElse(null))) {
            return;
        }
        members.findActive(employee.getUserId()).ifPresent(m -> events.publishEvent(new MailRequested(new OutgoingMail(
                m.email(), "Training assigned: " + course.getTitle(), """
                Hello %s,

                You have a new training: %s%s.

                See your trainings: %s/app/hrms/me
                """.formatted(m.name(), course.getTitle(), dueOn == null ? "" : ", due " + dueOn, appBaseUrl)))));
    }

    private List<TrainingAssignmentView> views(List<TrainingAssignment> list) {
        LocalDate today = clock.today();
        Map<UUID, Course> courseById = courses.byIds(list.stream().map(TrainingAssignment::getCourseId).toList());
        Map<UUID, Employee> people = employees.byIds(list.stream().map(TrainingAssignment::getEmployeeId).toList());
        Map<UUID, String> names = employees.names(people.values());
        Map<UUID, Members.Member> users = members.findAll(list.stream().map(TrainingAssignment::getAssignedBy)
                .filter(Objects::nonNull).collect(Collectors.toSet()));
        return list.stream().map(a -> {
            Course c = courseById.get(a.getCourseId());
            Members.Member by = a.getAssignedBy() == null ? null : users.get(a.getAssignedBy());
            boolean overdue = a.getStatus() == TrainingStatus.ASSIGNED && a.getDueOn() != null
                    && a.getDueOn().isBefore(today);
            return new TrainingAssignmentView(a.getId(), new CourseRef(c.getId(), c.getTitle()),
                    employees.ref(people.get(a.getEmployeeId()), names), a.getDueOn(), a.getStatus(), overdue,
                    by == null ? null : new MemberRef(by.id(), by.name()), a.getAssignedAt(), a.getCompletedOn(),
                    a.getCompletionNote(), a.getVersion());
        }).toList();
    }
}
