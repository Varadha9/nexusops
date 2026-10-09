package com.nexusops.hrms;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.TaskCommand;
import com.nexusops.collaboration.TaskPriority;
import com.nexusops.collaboration.TaskService;
import com.nexusops.collaboration.TaskStatus;
import com.nexusops.hrms.domain.Employee;
import com.nexusops.hrms.domain.EmployeeOnboarding;
import com.nexusops.hrms.domain.EmployeeOnboardingRepository;
import com.nexusops.hrms.domain.OnboardingTemplate;
import com.nexusops.hrms.domain.OnboardingTemplateItem;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.web.ApiProblem;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** D10: an onboarding checklist becomes one collaboration task per item on the EMPLOYEE subject. */
@Service
public class OnboardingService implements OnboardingReader, EmployeeHireHook {

    static final String ALREADY = "Onboarding has already started for this employee.";

    private final EmployeeOnboardingRepository onboardings;
    private final OnboardingTemplateService templates;
    private final TaskService tasks;
    private final EmployeeService employees;
    private final AuditService audit;

    OnboardingService(EmployeeOnboardingRepository onboardings, OnboardingTemplateService templates, TaskService tasks,
            @org.springframework.context.annotation.Lazy EmployeeService employees, AuditService audit) {
        this.onboardings = onboardings;
        this.templates = templates;
        this.tasks = tasks;
        this.employees = employees;
        this.audit = audit;
    }

    @Transactional
    public EmployeeView start(UUID employeeId, UUID templateId) {
        templates.resolve(templateId, "templateId"); // body 400 before the path 404 (global error order)
        Employee employee = employees.find(employeeId);
        begin(employee, templateId);
        return employees.get(employeeId);
    }

    @Override
    public void afterHire(Employee employee, EmployeeCommand command) {
        if (command.onboardingTemplateId() != null) {
            begin(employee, command.onboardingTemplateId());
        }
    }

    @Override
    @Transactional(readOnly = true)
    public OnboardingProgress progress(UUID employeeId) {
        return onboardings.findByEmployeeId(employeeId).map(o -> {
            List<UUID> ids = onboardings.taskIds(o.getId());
            Map<UUID, TaskStatus> statuses = tasks.statuses(ids);
            int done = (int) statuses.values().stream().filter(s -> s == TaskStatus.DONE).count();
            return new OnboardingProgress(o.getTemplateId(), o.getTemplateName(), done, statuses.size());
        }).orElse(null);
    }

    boolean inProgress(UUID employeeId) {
        OnboardingProgress p = progress(employeeId);
        return p != null && p.done() < p.total();
    }

    private void begin(Employee employee, UUID templateId) {
        OnboardingTemplate template = templates.resolve(templateId, "templateId");
        HrRecords.requireNotArchived(template.isArchived());
        if (onboardings.existsByEmployeeId(employee.getId())) {
            throw ApiProblem.conflict(ALREADY);
        }
        UUID starter = TenantContext.userId().orElse(null);
        UUID manager = employee.getManagerId() == null ? null
                : employees.byIds(List.of(employee.getManagerId())).get(employee.getManagerId()).getUserId();
        EmployeeOnboarding onboarding = onboardings.saveAndFlush(new EmployeeOnboarding(Ids.newId(), employee.getId(),
                template.getId(), template.getName(), starter));
        for (OnboardingTemplateItem item : templates.items(template.getId())) {
            UUID assignee = switch (item.getOwner()) {
                case HR -> starter;
                case MANAGER -> manager != null ? manager : starter;
                case EMPLOYEE -> employee.getUserId() != null ? employee.getUserId() : starter;
            };
            UUID taskId = tasks.create(new TaskCommand(item.getTitle(), item.getDescription(), TaskPriority.NORMAL,
                    employee.getStartDate().plusDays(item.getDueOffsetDays()), assignee, EmployeeSubjects.TYPE,
                    employee.getId())).id();
            onboardings.linkTask(Ids.newId(), TenantContext.requireTenantId(), onboarding.getId(), taskId);
        }
        audit.record(AuditEntry.of("OnboardingStarted", "Employee", employee.getId())
                .withAfter(Map.of("templateId", template.getId().toString(), "templateName", template.getName())));
    }
}
