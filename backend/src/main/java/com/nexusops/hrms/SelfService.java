package com.nexusops.hrms;

import com.nexusops.hrms.domain.Employee;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** D17: the caller's own employee record, and only theirs. */
@Service
public class SelfService {

    static final String NOT_LINKED = "Your account isn't linked to an employee record.";

    private final EmployeeService employees;
    private final LeaveService leave;
    private final TrainingService training;
    private final HrClock clock;

    SelfService(EmployeeService employees, LeaveService leave, TrainingService training, HrClock clock) {
        this.employees = employees;
        this.leave = leave;
        this.training = training;
        this.clock = clock;
    }

    @Transactional(readOnly = true)
    public MeView me() {
        Employee me = current();
        int year = clock.today().getYear();
        return new MeView(employees.get(me.getId()), year, leave.balancesFor(me, year), leave.forEmployee(me.getId()),
                training.openFor(me.getId()));
    }

    @Transactional
    public LeaveRequestView requestLeave(LeaveRequestCommand command) {
        return leave.requestOwn(current(), command);
    }

    @Transactional
    public LeaveRequestView cancelLeave(UUID id, Long version) {
        return leave.cancelOwn(current(), id, version);
    }

    @Transactional
    public TrainingAssignmentView completeTraining(UUID id, String note, Long version) {
        return training.completeOwn(current(), id, note, version);
    }

    private Employee current() {
        TenantContext.requireTenantId();
        return employees.forUser(TenantContext.userId().orElse(null))
                .orElseThrow(() -> ApiProblem.notFound(NOT_LINKED));
    }
}
