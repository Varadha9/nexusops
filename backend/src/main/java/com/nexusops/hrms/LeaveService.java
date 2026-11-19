package com.nexusops.hrms;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.hrms.domain.Employee;
import com.nexusops.hrms.domain.EmploymentDates;
import com.nexusops.hrms.domain.LeaveAdjustment;
import com.nexusops.hrms.domain.LeaveAdjustmentRepository;
import com.nexusops.hrms.domain.LeaveRequest;
import com.nexusops.hrms.domain.LeaveRequestRepository;
import com.nexusops.hrms.domain.LeaveType;
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
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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

/** Leave requests, balances and adjustments (D13, D14). Approver actions need hr.leave.approve (controllers). */
@Service
public class LeaveService {

    static final String YEAR_SPLIT = "Split a request at the end of the year.";
    static final String NO_WORKING_DAY = "Choose at least one working day.";
    static final String OVERLAP = "This overlaps another leave request.";
    static final String SHORT = "Not enough leave left: %s days available.";
    static final String OWN_DECISION = "Someone else must decide your own request.";
    static final String DECIDED = "This request has already been decided.";
    static final String NOT_CANCELLABLE = "This request can no longer be cancelled.";
    static final String BEFORE_EMPLOYMENT = "Leave can't start before the employment does.";
    static final String AFTER_EMPLOYMENT = "Leave can't continue after the employment ends.";
    static final String END_BEFORE_START = "The end date can't be before the start date.";
    private static final Set<LeaveStatus> LIVE = Set.of(LeaveStatus.PENDING, LeaveStatus.APPROVED);

    private final LeaveRequestRepository requests;
    private final LeaveAdjustmentRepository adjustments;
    private final LeaveTypeService types;
    private final HolidayService holidays;
    private final EmployeeService employees;
    private final Members members;
    private final HrClock clock;
    private final TenantLocks locks;
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final String appBaseUrl;

    LeaveService(LeaveRequestRepository requests, LeaveAdjustmentRepository adjustments, LeaveTypeService types,
            HolidayService holidays, EmployeeService employees, Members members, HrClock clock, TenantLocks locks,
            AuditService audit, ApplicationEventPublisher events, @Value("${nexusops.app.base-url}") String appBaseUrl) {
        this.requests = requests;
        this.adjustments = adjustments;
        this.types = types;
        this.holidays = holidays;
        this.employees = employees;
        this.members = members;
        this.clock = clock;
        this.locks = locks;
        this.audit = audit;
        this.events = events;
        this.appBaseUrl = appBaseUrl;
    }

    @Transactional(readOnly = true)
    public PageResponse<LeaveRequestView> list(LeaveQuery q, Integer page, Integer size) {
        TenantContext.requireTenantId();
        Specification<LeaveRequest> spec = (root, cq, cb) -> cb.and(Stream.of(
                q.status() == null ? null : cb.equal(root.get("status"), q.status()),
                q.employeeId() == null ? null : cb.equal(root.get("employeeId"), q.employeeId()),
                q.to() == null ? null : cb.lessThanOrEqualTo(root.get("startDate"), q.to()),
                q.from() == null ? null : cb.greaterThanOrEqualTo(root.get("endDate"), q.from()))
                .filter(Objects::nonNull).toArray(jakarta.persistence.criteria.Predicate[]::new));
        Pageable paging = Paging.of(page, size, Sort.by(Sort.Order.desc("startDate"), Sort.Order.asc("id")));
        Page<LeaveRequest> found = requests.findAll(spec, paging);
        return new PageResponse<>(views(found.getContent()), found.getNumber(), found.getSize(), found.getTotalElements());
    }

    /** An approver records leave for someone (D13). */
    @Transactional
    public LeaveRequestView request(UUID employeeId, LeaveRequestCommand c) {
        Employee employee = employees.resolve(employeeId, "employeeId");
        return create(employee, c);
    }

    @Transactional
    LeaveRequestView requestOwn(Employee employee, LeaveRequestCommand c) {
        return create(employee, c);
    }

    @Transactional
    public LeaveRequestView approve(UUID id, String note, Long version) {
        return decide(id, LeaveStatus.APPROVED, note, version, "LeaveApproved", "approved");
    }

    @Transactional
    public LeaveRequestView reject(UUID id, String note, Long version) {
        return decide(id, LeaveStatus.REJECTED, note, version, "LeaveRejected", "rejected");
    }

    /** Approvers: any live request until its last day has passed. */
    @Transactional
    public LeaveRequestView cancel(UUID id, Long version) {
        LeaveRequest r = find(id);
        HrRecords.checkVersion(r.getVersion(), version);
        if (!LIVE.contains(r.getStatus()) || r.getEndDate().isBefore(clock.today())) {
            throw ApiProblem.conflict(NOT_CANCELLABLE);
        }
        return doCancel(r);
    }

    /** The employee: pending ones, or approved ones that haven't started yet. Someone else's id is 404. */
    @Transactional
    LeaveRequestView cancelOwn(Employee employee, UUID id, Long version) {
        LeaveRequest r = find(id);
        if (!r.getEmployeeId().equals(employee.getId())) {
            throw ApiProblem.notFound(HrRecords.NOT_FOUND);
        }
        HrRecords.checkVersion(r.getVersion(), version);
        boolean pending = r.getStatus() == LeaveStatus.PENDING;
        boolean upcoming = r.getStatus() == LeaveStatus.APPROVED && r.getStartDate().isAfter(clock.today());
        if (!pending && !upcoming) {
            throw ApiProblem.conflict(NOT_CANCELLABLE);
        }
        return doCancel(r);
    }

    @Transactional(readOnly = true)
    public List<AwayView> away(LocalDate from, LocalDate to) {
        TenantContext.requireTenantId();
        LocalDate start = from == null ? clock.today() : from;
        LocalDate end = to == null ? start : to;
        List<LeaveRequest> found = requests.approvedBetween(start, end);
        Map<UUID, Employee> people = employees.byIds(found.stream().map(LeaveRequest::getEmployeeId).toList());
        Map<UUID, String> names = employees.names(people.values());
        Map<UUID, LeaveType> kinds = types.byIds(found.stream().map(LeaveRequest::getTypeId).toList());
        return found.stream().map(r -> new AwayView(employees.ref(people.get(r.getEmployeeId()), names),
                LeaveTypeService.ref(kinds.get(r.getTypeId())), r.getStartDate(), r.getEndDate())).toList();
    }

    @Transactional(readOnly = true)
    public List<LeaveBalanceView> balances(UUID employeeId, Integer year) {
        return balancesFor(employees.find(employeeId), year == null ? clock.today().getYear() : year);
    }

    List<LeaveBalanceView> balancesFor(Employee employee, int year) {
        return types.active().stream().map(t -> {
            LeaveBalance b = balance(employee.getId(), t, year);
            return new LeaveBalanceView(LeaveTypeService.ref(t), t.isPaid(), b.entitlement(), b.taken(), b.pending(),
                    b.available());
        }).toList();
    }

    @Transactional
    public List<LeaveBalanceView> adjust(UUID employeeId, LeaveAdjustmentCommand c) {
        BigDecimal days = c.days();
        if (days == null || days.signum() == 0 || days.abs().compareTo(BigDecimal.valueOf(366)) > 0
                || days.multiply(BigDecimal.TWO).stripTrailingZeros().scale() > 0) {
            throw ApiProblem.badRequestField("days", "Use a non-zero number of days in steps of 0.5, at most 366.");
        }
        if (c.year() == null || c.year() < 2000 || c.year() > 2100) {
            throw ApiProblem.badRequestField("year", "Enter a year.");
        }
        String reason = Text.required(c.reason(), 200, "reason");
        LeaveType type = types.resolve(c.typeId(), "typeId");
        Employee employee = employees.find(employeeId);
        locks.lock(EmployeeService.leaveLock(employee.getId())); // a concurrent request can't overdraw meanwhile
        LeaveAdjustment adjustment = adjustments.saveAndFlush(new LeaveAdjustment(Ids.newId(), employee.getId(),
                type.getId(), c.year(), days, reason, TenantContext.userId().orElse(null), Instant.now()));
        audit.record(AuditEntry.of("LeaveAdjusted", "Employee", employee.getId()).withAfter(Map.of("typeId",
                type.getId().toString(), "year", c.year(), "days", days.toPlainString(), "reason", reason,
                "adjustmentId", adjustment.getId().toString())));
        return balancesFor(employee, c.year());
    }

    List<LeaveRequestView> forEmployee(UUID employeeId) {
        return views(requests.findByEmployeeIdOrderByStartDateDesc(employeeId));
    }

    long pendingCount() {
        return requests.countByStatus(LeaveStatus.PENDING);
    }

    /** D4: leave starting after the last day of employment is cancelled; leave before it stays. */
    @EventListener
    void on(EmploymentEndedEvent event) {
        for (LeaveRequest r : requests.startingAfter(event.employeeId(), LIVE, event.endDate())) {
            r.cancel(TenantContext.userId().orElse(null));
            audit.record(AuditEntry.of("LeaveCancelled", "LeaveRequest", r.getId())
                    .withAfter(Map.of("reason", "Employment ended")));
        }
        requests.flush();
    }

    // ---- internals ----

    private LeaveRequestView create(Employee employee, LeaveRequestCommand c) {
        LeaveType type = types.resolve(c.typeId(), "typeId");
        if (c.startDate() == null) {
            throw ApiProblem.badRequestField("startDate", "Enter the first day of leave.");
        }
        if (c.endDate() == null) {
            throw ApiProblem.badRequestField("endDate", "Enter the last day of leave.");
        }
        if (c.endDate().isBefore(c.startDate())) {
            throw ApiProblem.badRequestField("endDate", END_BEFORE_START);
        }
        if (c.endDate().getYear() != c.startDate().getYear()) {
            throw ApiProblem.badRequestField("endDate", YEAR_SPLIT);
        }
        String reason = Text.optional(c.reason(), 500, "reason");
        requireWithinEmployment(c, employee.getStartDate(), employee.getEndDate());
        int days = WorkingDays.count(c.startDate(), c.endDate(), holidays.between(c.startDate(), c.endDate()));
        if (days < 1) {
            throw ApiProblem.badRequestField("endDate", NO_WORKING_DAY);
        }
        HrRecords.requireNotArchived(type.isArchived());
        locks.lock(EmployeeService.leaveLock(employee.getId()));
        // I-1: the employment may have ended while we waited; check against the committed dates.
        EmploymentDates now = employees.currentDates(employee.getId());
        requireWithinEmployment(c, now.startDate(), now.endDate());
        if (requests.overlaps(employee.getId(), LIVE, c.startDate(), c.endDate())) {
            throw ApiProblem.conflict(OVERLAP);
        }
        LeaveBalance b = balance(employee.getId(), type, c.startDate().getYear());
        if (b.available() != null && b.available().compareTo(BigDecimal.valueOf(days)) < 0) {
            throw ApiProblem.conflict(SHORT.formatted(LeaveBalance.days(b.available().max(BigDecimal.ZERO))));
        }
        LeaveRequest r = requests.saveAndFlush(new LeaveRequest(Ids.newId(), employee.getId(), type.getId(),
                c.startDate(), c.endDate(), days, reason, TenantContext.userId().orElse(null)));
        audit.record(AuditEntry.of("LeaveRequested", "LeaveRequest", r.getId()).withAfter(Map.of("employeeId",
                employee.getId().toString(), "typeId", type.getId().toString(), "startDate", c.startDate().toString(),
                "endDate", c.endDate().toString(), "days", days)));
        notifyManager(employee, r);
        return views(List.of(r)).getFirst();
    }

    private static void requireWithinEmployment(LeaveRequestCommand c, LocalDate start, LocalDate end) {
        if (c.startDate().isBefore(start)) {
            throw ApiProblem.badRequestField("startDate", BEFORE_EMPLOYMENT);
        }
        if (end != null && c.endDate().isAfter(end)) {
            throw ApiProblem.badRequestField("endDate", AFTER_EMPLOYMENT);
        }
    }

    private LeaveRequestView decide(UUID id, LeaveStatus outcome, String rawNote, Long version, String action,
            String word) {
        LeaveRequest r = find(id);
        HrRecords.checkVersion(r.getVersion(), version);
        String note = Text.optional(rawNote, 500, "note");
        if (r.getStatus() != LeaveStatus.PENDING) {
            throw ApiProblem.conflict(DECIDED);
        }
        Employee employee = employees.find(r.getEmployeeId());
        UUID me = TenantContext.userId().orElse(null);
        if (me != null && me.equals(employee.getUserId())) {
            throw ApiProblem.conflict(OWN_DECISION);
        }
        r.decide(outcome, me, note);
        requests.flush();
        audit.record(AuditEntry.of(action, "LeaveRequest", id).withAfter(note == null ? Map.of() : Map.of("note", note)));
        notifyEmployee(employee, r, word);
        return views(List.of(r)).getFirst();
    }

    private LeaveRequestView doCancel(LeaveRequest r) {
        r.cancel(TenantContext.userId().orElse(null));
        requests.flush();
        audit.record(AuditEntry.of("LeaveCancelled", "LeaveRequest", r.getId()));
        return views(List.of(r)).getFirst();
    }

    private LeaveBalance balance(UUID employeeId, LeaveType type, int year) {
        LocalDate from = LocalDate.of(year, 1, 1);
        LocalDate to = LocalDate.of(year, 12, 31);
        int taken = (int) requests.sumDays(employeeId, type.getId(), LeaveStatus.APPROVED, from, to);
        int pending = (int) requests.sumDays(employeeId, type.getId(), LeaveStatus.PENDING, from, to);
        BigDecimal adjusted = adjustments.sumDays(employeeId, type.getId(), year);
        return LeaveBalance.of(type.getAnnualAllowance(), adjusted == null ? BigDecimal.ZERO : adjusted, taken,
                pending);
    }

    private LeaveRequest find(UUID id) {
        TenantContext.requireTenantId();
        return requests.findById(id).orElseThrow(() -> ApiProblem.notFound(HrRecords.NOT_FOUND));
    }

    private void notifyManager(Employee employee, LeaveRequest r) {
        if (employee.getManagerId() == null) {
            return;
        }
        Employee manager = employees.byIds(List.of(employee.getManagerId())).get(employee.getManagerId());
        if (manager == null || manager.getUserId() == null
                || manager.getUserId().equals(TenantContext.userId().orElse(null))) {
            return;
        }
        members.findActive(manager.getUserId()).ifPresent(m -> {
            String name = employees.names(List.of(employee)).get(employee.getId());
            events.publishEvent(new MailRequested(new OutgoingMail(m.email(),
                    "Leave request: " + name + ", " + r.getStartDate() + " to " + r.getEndDate(), """
                    Hello %s,

                    %s asked for %d working day(s) of leave, %s to %s.

                    Review it: %s/app/hrms/leave
                    """.formatted(m.name(), name, r.getDays(), r.getStartDate(), r.getEndDate(), appBaseUrl))));
        });
    }

    private void notifyEmployee(Employee employee, LeaveRequest r, String word) {
        if (employee.getUserId() == null) {
            return;
        }
        members.findActive(employee.getUserId()).ifPresent(m -> events.publishEvent(new MailRequested(new OutgoingMail(
                m.email(), "Your leave request was " + word, """
                Hello %s,

                Your leave from %s to %s (%d working day(s)) was %s.%s

                See your leave: %s/app/hrms/me
                """.formatted(m.name(), r.getStartDate(), r.getEndDate(), r.getDays(), word,
                r.getDecisionNote() == null ? "" : "\n\nNote: " + r.getDecisionNote(), appBaseUrl)))));
    }

    private List<LeaveRequestView> views(List<LeaveRequest> list) {
        Map<UUID, Employee> people = employees.byIds(list.stream().map(LeaveRequest::getEmployeeId).toList());
        Map<UUID, String> names = employees.names(people.values());
        Map<UUID, LeaveType> kinds = types.byIds(list.stream().map(LeaveRequest::getTypeId).toList());
        Map<UUID, Members.Member> users = members.findAll(list.stream()
                .flatMap(r -> Stream.of(r.getRequestedBy(), r.getDecidedBy())).filter(Objects::nonNull)
                .collect(Collectors.toSet()));
        return list.stream().map(r -> new LeaveRequestView(r.getId(), employees.ref(people.get(r.getEmployeeId()), names),
                LeaveTypeService.ref(kinds.get(r.getTypeId())), r.getStartDate(), r.getEndDate(), r.getDays(),
                r.getReason(), r.getStatus(), member(users, r.getRequestedBy()), r.getRequestedAt(),
                member(users, r.getDecidedBy()), r.getDecidedAt(), r.getDecisionNote(), r.getCancelledAt(),
                r.getVersion())).toList();
    }

    private static MemberRef member(Map<UUID, Members.Member> users, UUID id) {
        Members.Member m = id == null ? null : users.get(id);
        return m == null ? null : new MemberRef(m.id(), m.name());
    }
}
