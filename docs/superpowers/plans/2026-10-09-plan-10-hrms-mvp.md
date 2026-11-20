# Phase 8 — HRMS MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Employees who are the canonical directory persons. Each has an employment record with a department, a job role that needs skills, a manager and an optional sign-in account. Around that record:
- onboarding checklists that create real tasks;
- skill gaps with recommended training, where completing a training raises the skill;
- leave counted in working days against balances, with self-service requests and approvals;
- employee documents, a searchable policy library, and a workforce dashboard.

All of it sits inside the HRMS module and is isolated per tenant.

**Architecture:** A new Spring Modulith module `hrms` (`com.nexusops.hrms`) owns the HR tables (Flyway V28–V33).
- An employee record sits beside the directory person. HRMS keeps the person's `EMPLOYEE` role in step through a new `PartyService.recordEmployment`, and a new directory SPI `EmploymentGuard` stops manual edits of that role while HRMS manages it.
- Pure classes hold the rules: `WorkingDays`, `LeaveBalance`, `EmploymentStatus`, `SkillGaps`, `ManagerChain`.
- Employees are collaboration subjects, so tasks (onboarding), documents and activity work on them unchanged.
- The React app gets an HRMS area, a My HR self-service page and Settings → HRMS.

**Tech Stack:** Java 25, Spring Boot 4.1 (Web MVC, Security, Data JPA, Modulith), Hibernate `@TenantId`, PostgreSQL 17 RLS and full-text search, Flyway, JUnit 5 + Testcontainers + MockMvc; React 19 + TypeScript + Vite, TanStack Query, React Hook Form + Zod 4, Tailwind/shadcn, Vitest + Testing Library, Playwright.

**Spec:** `docs/superpowers/specs/2026-10-09-hrms-mvp-design.md` (decisions D1–D22 are referenced below).

## Global Constraints

- **Tables:**
  - Every new table has `tenant_id uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE` and `UNIQUE (tenant_id, id)`.
  - It enables and forces row-level security, with a policy `tenant_isolation` using `USING` and `WITH CHECK` `(tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)`, in the same migration as the table.
  - Every new table joins `RlsCoverageIT.EXPECTED_TENANT_TABLES` in the task that creates it.
- **References inside a tenant** use composite FKs `(tenant_id, x_id) REFERENCES t (tenant_id, id)`. Member references (`user_id`, `created_by`, `author_id`, `decided_by`) are `uuid REFERENCES users (id) ON DELETE SET NULL`.
- **Permissions:**
  - Every HRMS permission has `module_code = 'HRMS'`: `hr.employee.read`, `hr.employee.update` (both already in V3), `hr.settings.manage`, `hr.leave.approve`, `hr.training.manage`, `hr.policy.manage`, `hr.self.service`.
  - System roles get every code (`grant_to_system_roles`).
- **Routes and authorization:** every handler has `@PreAuthorize`, and routes live under `/api/v1/hr/…`.
- **Error order:** invalid body 400 → missing permission 403 → path id not found (incl. other tenant) 404 → state conflict 409.
  - A stale version 409 may come before a body 400 on edits; this is the codebase convention.
  - Resolve every referenced id first (400s), then check state (409s).
  - Body references to unknown or other-tenant ids are 400 field errors.
- **Messages,** verbatim (spec D22):
  - `"Record not found."`
  - `"This record is archived."`
  - `"This record was changed by someone else. Reload and try again."`
  - `"You do not have permission to perform this action."`
  - `"Reload the record and try again."` (missing version)
  - `"Choose a person in this workspace."`
  - `"This person already has an employee record."`
  - `"Choose an active team member."`
  - `"This team member is already linked to an employee."`
  - `"A manager can't report to their own report."`
  - `"This person's employment is managed in HRMS."`
  - `"Move this department's employees first."`
  - `"Split a request at the end of the year."`
  - `"Choose at least one working day."`
  - `"This overlaps another leave request."`
  - `"Not enough leave left: N days available."` (N printed without trailing zeros: `2`, `2.5`)
  - `"Someone else must decide your own request."`
  - `"Your account isn't linked to an employee record."`
- **"Today"** for HR rules (employment status, leave cancellation windows, overdue trainings, dashboard windows) is the current date in the workspace time zone: `TenantDirectory.currentSettings().timezone()`, falling back to UTC when blank or invalid. The helper is `hrms.HrClock.today()` (Task 2).
- **Database access:**
  - JDBC (non-JPA) queries always add an explicit `tenant_id = :tenant` predicate on top of RLS.
  - Full-text queries bind user text as parameters.
- **Optimistic locking and email:**
  - Every edit of a versioned record takes its optimistic lock (`@Version`) and flushes before side effects.
  - Emails go out after commit through `MailRequested(new OutgoingMail(to, subject, text))`.
  - Links use `@Value("${nexusops.app.base-url}")`.
- **Audit actions,** verbatim:
  - Departments: `DepartmentCreated`, `DepartmentUpdated`, `DepartmentArchived`, `DepartmentRestored`.
  - Skills: `SkillCreated`, `SkillUpdated`, `SkillArchived`, `SkillRestored`.
  - Job roles: `JobRoleCreated`, `JobRoleUpdated`, `JobRoleArchived`, `JobRoleRestored`.
  - Employees: `EmployeeHired`, `EmployeeUpdated`, `EmploymentEnded`, `EmploymentReinstated`, `EmployeeSkillsUpdated`.
  - Onboarding: `OnboardingTemplateCreated`, `OnboardingTemplateUpdated`, `OnboardingTemplateArchived`, `OnboardingTemplateRestored`, `OnboardingStarted`.
  - Leave: `LeaveTypeCreated`, `LeaveTypeUpdated`, `LeaveTypeArchived`, `LeaveTypeRestored`, `HolidayAdded`, `HolidayRemoved`, `LeaveRequested`, `LeaveApproved`, `LeaveRejected`, `LeaveCancelled`, `LeaveAdjusted`.
  - Training: `CourseCreated`, `CourseUpdated`, `CourseArchived`, `CourseRestored`, `TrainingAssigned`, `TrainingCompleted`, `TrainingCancelled`.
  - Policies: `PolicyCreated`, `PolicyUpdated`, `PolicyPublished`, `PolicyUnpublished`, `PolicyArchived`.
- **Commits** use conventional messages, with **no `Co-Authored-By` or any AI attribution trailer** (the repository owner's rule). Never push.
- **Pattern references:**
  - Where a step says "mirror `<existing file>`", that file is committed in the repository. Reproduce its structure (constructor injection, `find`/`checkVersion`/`snapshot` helpers, repository finder names, controller shape) with the fields, messages and rules the step lists.
  - Everything the task's tests assert is spelled out in full in the task.
- **Commands:**
  - Backend tests: `cd backend && ./gradlew test` (Docker running).
  - Frontend: `cd frontend && npx prettier --write src e2e && npm run format:check && npm run lint && npm run typecheck && npm test`.
- **Frontend tests:**
  - `setup()` helpers `return renderApp({ server, path })`.
  - After an action whose page refetches, register the new `GET` reply **after the action button has appeared** (`await screen.findByRole(...)`) and before clicking it.
  - Detail pages keyed by a route param render their body with `key={param}` from a thin wrapper.
  - Uncontrolled search inputs get `key={q}`.

## Review Focus

1. **A leave request that starts or ends next to a weekend or holiday** counts only working days: Fri–Mon is 2, and a Sat–Sun-only range is refused with "Choose at least one working day.". Owners: Task 4 `WorkingDaysTest.fridayToMondayIsTwoDays` and `LeaveApiIT.aWeekendOnlyRequestIsRefused`.
2. **Ending employment with an end date in the past or the future** cancels only pending and approved leave that starts **after** the end date. Leave already taken before it stays approved, and balances still count it. Owner: Task 4 `LeaveApiIT.endingEmploymentCancelsOnlyLaterLeave`.
3. **A manager loop through more than one level** is refused: with A managed by B and B managed by C, making A C's manager fails, as does an employee managing themselves. Owners: Task 2 `ManagerChainTest` and `EmployeeApiIT.managerLoopsAreRefused`.
4. **A signed-in employee reaching another employee's leave request or training assignment** through the self-service routes gets 404, and an unlinked account gets the "isn't linked" 404. Owner: Task 6 `SelfServiceApiIT.selfServiceNeverReachesSomeoneElse`.
5. **Fractional balances from half-day adjustments** print correctly in the refusal, e.g. "Not enough leave left: 2.5 days available.", and a request exactly equal to the available balance is accepted. Owner: Task 4 `LeaveApiIT.balanceCanBeUsedExactlyAndFractionsPrint`.

## File Structure

```
backend/src/main/resources/db/migration/
  V28__hr_base.sql            permissions (D20), departments, skills, job_roles, job_role_skills
  V29__employees.sql          employees, employee_skills
  V30__onboarding.sql         onboarding_templates, onboarding_template_items, employee_onboardings,
                              employee_onboarding_tasks (+ seed "Standard onboarding")
  V31__leave.sql              leave_types (+ seeds), holidays, leave_requests, leave_adjustments (append-only)
  V32__training.sql           courses, course_skills, training_assignments
  V33__hr_policies.sql        hr_policies (+ generated tsvector, GIN)
backend/src/main/java/com/nexusops/
  directory/EmploymentGuard.java (SPI), PartyService.java (+ recordEmployment, guard call in setRole)
  collaboration/TaskService.java (+ statuses(Collection<UUID>))
  collaboration/SearchService.java (ORDER gains EMPLOYEE)
  hrms/package-info.java, HrPermissions.java, HrClock.java, HrSetup.java, HrDtos (web)
  hrms/SkillService, DepartmentService, JobRoleService (+ views/commands)
  hrms/EmploymentStatus, ManagerChain, EmployeeService, EmployeeView/Summary/Command/Query, EmployeeSubjects,
      EmployeeRelations, HrmsEmploymentGuard, SkillGaps, SkillGapView, EmploymentEndedEvent
  hrms/OnboardingService, OnboardingTemplateView/Command, OnboardingProgress
  hrms/WorkingDays, LeaveBalance, LeaveTypeService, HolidayService, LeaveService, LeaveRequestView, LeaveBalanceView,
      AwayView
  hrms/CourseService, TrainingService, CourseView, TrainingAssignmentView
  hrms/PolicyService, PolicyView/Summary, SelfService (MeView)
  hrms/HrQueries (dashboard SQL), HrDashboardView
  hrms/domain/…  Department, Skill, JobRole(+Skill), Employee(+Skill), OnboardingTemplate(+Item), EmployeeOnboarding,
      LeaveType, Holiday, LeaveRequest, LeaveAdjustment, Course(+Skill), TrainingAssignment, HrPolicy (+ repositories)
  hrms/web/…  controllers per area
backend/src/test/java/com/nexusops/
  support/TestHr.java
  hrms/*IT, *Test; HrRlsIT; HrModuleGateIT; RlsCoverageIT, CrossTenantApiIT, OpenApiContractIT, SearchApiIT (modified)
docs/decisions/0013-hrms-employment-and-leave.md; docs/api/openapi.json (re-exported)
frontend/src/
  lib/api/types.ts, features/auth/permissions.tsx, test/records.ts, features/records/SubjectLink.tsx,
  features/shell/GlobalSearch.tsx, routes.tsx, nav.ts, ComingSoonPage.test.tsx
  features/hrms/… layout, index/overview, employees, employee page, leave, training, policies, my HR, pickers, labels
  features/settings/HrSettingsPage.tsx (+ sections)
  features/directory/PartyDetailPage.tsx (link to employee record)
frontend/e2e/hrms.spec.ts; README.md
```

## Pre-flight rulings (made while writing this plan)

- **Employee numbers** are entered by HR (no automatic sequence). They are unique per workspace ignoring case. HRMS mirrors them onto the directory role, whose existing unique index enforces the same rule.
- **"Today"** comes from `HrClock`, which reads `Clock.systemUTC()` in the workspace's zone. Tests that need a fixed date backdate rows with `OwnerJdbc` instead of moving the clock.
- **Ending employment** publishes an in-module `EmploymentEndedEvent(employeeId, endDate)`, handled synchronously in the same transaction (`@EventListener`) by `LeaveService` (Task 4) and `TrainingService` (Task 5). Task 2 publishes the event before any listener exists.
- **Skill-gap recommendations** come from courses (Task 5). In Task 2 the gap lists `recommendedCourses: []`. Task 5 fills it in through a package-private `CourseService.coursesTeaching(skillIds)` that `SkillGaps` callers use.
- **Onboarding progress** reads task statuses through a new `TaskService.statuses(Collection<UUID>) → Map<UUID, TaskStatus>` (collaboration API, no permission check, tenant-scoped).
- **Leave approvals:**
  - Approvals need `hr.leave.approve`.
  - The manager is notified by email when they have a linked account, but approving still needs the permission. Manager-scoped approval without it is later work.
  - Approvers can't decide their own requests.
- **Two additions to the spec's API**, made while planning: `GET employees?personId=` (finds a person's record in any status, for the directory link) and `GET members?q=` (`hr.employee.update`; active team members to link). The spec's §5 table gains both.
- **Balances** use a type's current allowance, so changing an allowance changes every year's entitlement. This is acceptable for the MVP and noted in ADR-0013.

---

### Task 1: HRMS permissions, skills, departments and job roles with required skills

**Files:**
- Create: `backend/src/main/resources/db/migration/V28__hr_base.sql`
- Create: `backend/src/main/java/com/nexusops/hrms/package-info.java`, `HrPermissions.java`, `SkillRef.java`, `SkillView.java`, `SkillService.java`, `DepartmentRef.java`, `DepartmentCommand.java`, `DepartmentView.java`, `DepartmentService.java`, `RequiredSkill.java`, `RequiredSkillView.java`, `JobRoleRef.java`, `JobRoleCommand.java`, `JobRoleView.java`, `JobRoleService.java`
- Create: `backend/src/main/java/com/nexusops/hrms/domain/Skill.java`, `SkillRepository.java`, `Department.java`, `DepartmentRepository.java`, `JobRole.java`, `JobRoleRepository.java`, `JobRoleSkill.java`, `JobRoleSkillRepository.java`
- Create: `backend/src/main/java/com/nexusops/hrms/web/HrDtos.java`, `SkillController.java`, `DepartmentController.java`, `JobRoleController.java`
- Create: `backend/src/test/java/com/nexusops/support/TestHr.java`, `backend/src/test/java/com/nexusops/hrms/SkillApiIT.java`, `DepartmentApiIT.java`, `JobRoleApiIT.java`
- Modify: `backend/src/test/java/com/nexusops/RlsCoverageIT.java` (add `departments`, `skills`, `job_roles`, `job_role_skills` to `EXPECTED_TENANT_TABLES`)

**Interfaces:**
- Produces (later tasks rely on these exact names):
  - `HrPermissions` constants `EMPLOYEE_READ = "hr.employee.read"`, `EMPLOYEE_UPDATE = "hr.employee.update"`, `SETTINGS_MANAGE = "hr.settings.manage"`, `LEAVE_APPROVE = "hr.leave.approve"`, `TRAINING_MANAGE = "hr.training.manage"`, `POLICY_MANAGE = "hr.policy.manage"`, `SELF_SERVICE = "hr.self.service"`.
  - Records `SkillRef(UUID id, String name)`, `SkillView(UUID id, String name, Instant archivedAt, long version)`, `DepartmentRef(UUID id, String name)`, `DepartmentView(UUID id, String name, String description, Instant archivedAt, long version)`, `DepartmentCommand(String name, String description)`, `RequiredSkill(UUID skillId, Integer minimumLevel)`, `RequiredSkillView(UUID skillId, String name, int minimumLevel)`, `JobRoleRef(UUID id, String title)`, `JobRoleCommand(String title, UUID departmentId, String description, List<RequiredSkill> requiredSkills)`, `JobRoleView(UUID id, String title, DepartmentRef department, String description, List<RequiredSkillView> requiredSkills, Instant archivedAt, long version)`.
  - `SkillService`:
    - public `list(boolean archived)`, `create(String name)`, `update(UUID, String name, Long version)`, `archive(UUID)`, `restore(UUID)`;
    - package-private `Skill resolve(UUID id, String field)` (unknown → 400 field `"Choose a skill in this workspace."`);
    - `Map<UUID, Skill> byIds(Collection<UUID>)`;
    - `static SkillRef ref(Skill)`.
  - `DepartmentService`:
    - public `list(boolean archived)`, `create(DepartmentCommand)`, `update(UUID, DepartmentCommand, Long)`, `archive(UUID)`, `restore(UUID)`;
    - package-private `Department resolve(UUID, String field)` (400 `"Choose a department in this workspace."`);
    - `Map<UUID, Department> byIds(Collection<UUID>)`;
    - `static DepartmentRef ref(Department)`.
  - `JobRoleService`:
    - public `list(boolean archived, UUID departmentId)`, `get(UUID)`, `create(JobRoleCommand)`, `update(UUID, JobRoleCommand, Long)`, `archive(UUID)`, `restore(UUID)`;
    - package-private `JobRole resolve(UUID, String field)` (400 `"Choose a job role in this workspace."`);
    - `Map<UUID, JobRole> byIds(Collection<UUID>)`;
    - `static JobRoleRef ref(JobRole)`;
    - `Map<UUID, Integer> requiredLevels(UUID jobRoleId)` (skill id → minimum level).
  - Static helper `HrRecords.requireNotArchived(boolean archived)` (409 `"This record is archived."`), in `hrms/HrRecords.java`, together with `checkVersion(long current, Long requested)`, `NOT_FOUND`, `STALE`, `ARCHIVED`, `FORBIDDEN`.
  - Test helper `support.TestHr`: `enable(Api)`, `skill(Api, String) → UUID`, `department(Api, String) → UUID`, `jobRole(Api, String title, UUID departmentId, Map<UUID,Integer> required) → UUID`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/support/TestHr.java`:

```java
package com.nexusops.support;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/** Enables the HRMS module and creates its reference records for tests. */
public final class TestHr {

    private TestHr() {}

    public static void enable(Api owner) throws Exception {
        owner.put("/api/v1/tenant/modules/HRMS", "{\"enabled\":true}").andExpect(status().isOk());
    }

    public static UUID skill(Api owner, String name) throws Exception {
        return Api.id(owner.post("/api/v1/hr/skills", "{\"name\":\"" + name + "\"}").andExpect(status().isCreated()));
    }

    public static UUID department(Api owner, String name) throws Exception {
        return Api.id(owner.post("/api/v1/hr/departments", "{\"name\":\"" + name + "\"}")
                .andExpect(status().isCreated()));
    }

    public static UUID jobRole(Api owner, String title, UUID departmentId, Map<UUID, Integer> required)
            throws Exception {
        String skills = required.entrySet().stream()
                .map(e -> "{\"skillId\":\"" + e.getKey() + "\",\"minimumLevel\":" + e.getValue() + "}")
                .collect(Collectors.joining(",", "[", "]"));
        String department = departmentId == null ? "null" : "\"" + departmentId + "\"";
        return Api.id(owner.post("/api/v1/hr/job-roles", "{\"title\":\"" + title + "\",\"departmentId\":" + department
                + ",\"requiredSkills\":" + skills + "}").andExpect(status().isCreated()));
    }
}
```

`backend/src/test/java/com/nexusops/hrms/SkillApiIT.java`:

```java
package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class SkillApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrskill"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
    }

    private long audits(String action) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select count(*) from audit_events where action = ?",
                Long.class, action);
    }

    @Test
    void createsRenamesArchivesAndRestores() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/skills", "{\"name\":\"  Customer   service \"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.name").value("Customer service")));
        TestHr.skill(owner, "Accounting");
        owner.post("/api/v1/hr/skills", "{\"name\":\"CUSTOMER SERVICE\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.put("/api/v1/hr/skills/" + id, "{\"name\":\"Customer care\",\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1));
        owner.put("/api/v1/hr/skills/" + id, "{\"name\":\"Customer care\",\"version\":0}")
                .andExpect(status().isConflict());
        owner.get("/api/v1/hr/skills").andExpect(jsonPath("$[*].name").value(Matchers.contains("Accounting",
                "Customer care")));
        owner.post("/api/v1/hr/skills/" + id + "/archive", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").exists());
        owner.get("/api/v1/hr/skills").andExpect(jsonPath("$[*].name").value(Matchers.contains("Accounting")));
        owner.get("/api/v1/hr/skills?archived=true").andExpect(jsonPath("$[*].name").value(
                Matchers.contains("Customer care")));
        owner.post("/api/v1/hr/skills/" + id + "/restore", "").andExpect(status().isOk())
                .andExpect(jsonPath("$.archivedAt").doesNotExist());
        assertThat(audits("SkillCreated")).isEqualTo(2);
        assertThat(audits("SkillUpdated")).isEqualTo(1);
        assertThat(audits("SkillArchived")).isEqualTo(1);
        assertThat(audits("SkillRestored")).isEqualTo(1);
    }

    @Test
    void invalidSkillsAreFieldErrors() throws Exception {
        owner.post("/api/v1/hr/skills", "{\"name\":\"\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.post("/api/v1/hr/skills", "{\"name\":\"" + "x".repeat(61) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.put("/api/v1/hr/skills/" + UUID.randomUUID(), "{\"name\":\"X\",\"version\":0}")
                .andExpect(status().isNotFound());
        UUID id = TestHr.skill(owner, "Welding");
        owner.put("/api/v1/hr/skills/" + id, "{\"name\":\"Welding\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("version"));
    }

    @Test
    void readersSeeSkillsButOnlySettingsManagersChangeThem() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "HR viewer", "hr.employee.read");
        Api viewer = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        viewer.get("/api/v1/hr/skills").andExpect(status().isOk());
        viewer.post("/api/v1/hr/skills", "{\"name\":\"X\"}").andExpect(status().isForbidden());
    }
}
```

`backend/src/test/java/com/nexusops/hrms/DepartmentApiIT.java`:

```java
package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class DepartmentApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrdept"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
    }

    @Test
    void createsRenamesArchivesAndRestores() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/departments", "{\"name\":\"Sales\",\"description\":\"Field sales\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.description").value("Field sales")));
        owner.post("/api/v1/hr/departments", "{\"name\":\"sales\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.put("/api/v1/hr/departments/" + id, "{\"name\":\"Sales & Marketing\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.description").doesNotExist());
        owner.post("/api/v1/hr/departments/" + id + "/archive", "").andExpect(status().isOk());
        owner.get("/api/v1/hr/departments?archived=true").andExpect(jsonPath("$[*].name").value(
                Matchers.contains("Sales & Marketing")));
        owner.put("/api/v1/hr/departments/" + id, "{\"name\":\"Sales\",\"version\":2}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This record is archived."));
        owner.post("/api/v1/hr/departments/" + id + "/restore", "").andExpect(status().isOk());
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action like 'Department%'", Long.class)).isEqualTo(4);
    }

    @Test
    void invalidDepartmentsAreFieldErrors() throws Exception {
        owner.post("/api/v1/hr/departments", "{\"name\":\"" + "x".repeat(81) + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.post("/api/v1/hr/departments", "{\"name\":\"Ok\",\"description\":\"" + "x".repeat(501) + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("description"));
    }
}
```

(Archiving a department with employees is Task 2's test.)

`backend/src/test/java/com/nexusops/hrms/JobRoleApiIT.java`:

```java
package com.nexusops.hrms;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestTenants;
import java.util.Map;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class JobRoleApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Api owner;
    UUID sales;
    UUID negotiation;
    UUID excel;

    @BeforeEach
    void workspace() throws Exception {
        owner = Api.login(mvc, TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrrole")));
        TestHr.enable(owner);
        sales = TestHr.department(owner, "Sales");
        negotiation = TestHr.skill(owner, "Negotiation");
        excel = TestHr.skill(owner, "Excel");
    }

    @Test
    void aJobRoleNamesTheSkillsItNeeds() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/job-roles", "{\"title\":\"Account executive\",\"departmentId\":\""
                + sales + "\",\"description\":\"Sells\",\"requiredSkills\":[{\"skillId\":\"" + negotiation
                + "\",\"minimumLevel\":4},{\"skillId\":\"" + excel + "\",\"minimumLevel\":2}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.department.name").value("Sales"))
                .andExpect(jsonPath("$.requiredSkills[*].name").value(Matchers.contains("Excel", "Negotiation")))
                .andExpect(jsonPath("$.requiredSkills[1].minimumLevel").value(4)));
        owner.put("/api/v1/hr/job-roles/" + id, "{\"title\":\"Account executive\",\"requiredSkills\":[{\"skillId\":\""
                + excel + "\",\"minimumLevel\":3}],\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.department").doesNotExist())
                .andExpect(jsonPath("$.requiredSkills.length()").value(1))
                .andExpect(jsonPath("$.requiredSkills[0].minimumLevel").value(3));
        owner.get("/api/v1/hr/job-roles/" + id).andExpect(jsonPath("$.version").value(1));
        UUID other = TestHr.jobRole(owner, "Sales manager", sales, Map.of(negotiation, 5));
        owner.get("/api/v1/hr/job-roles?departmentId=" + sales).andExpect(jsonPath("$[*].id").value(
                Matchers.contains(other.toString())));
        owner.post("/api/v1/hr/job-roles/" + id + "/archive", "").andExpect(status().isOk());
        owner.get("/api/v1/hr/job-roles").andExpect(jsonPath("$[*].title").value(Matchers.contains("Sales manager")));
    }

    @Test
    void requiredSkillsAreValidated() throws Exception {
        String[][] cases = {
                {"{\"title\":\"\"}", "title"},
                {"{\"title\":\"R\",\"departmentId\":\"" + UUID.randomUUID() + "\"}", "departmentId"},
                {"{\"title\":\"R\",\"requiredSkills\":[{\"skillId\":\"" + UUID.randomUUID()
                        + "\",\"minimumLevel\":2}]}", "requiredSkills[0].skillId"},
                {"{\"title\":\"R\",\"requiredSkills\":[{\"skillId\":\"" + excel + "\",\"minimumLevel\":6}]}",
                        "requiredSkills[0].minimumLevel"},
                {"{\"title\":\"R\",\"requiredSkills\":[{\"skillId\":\"" + excel + "\"}]}",
                        "requiredSkills[0].minimumLevel"},
                {"{\"title\":\"R\",\"requiredSkills\":[{\"skillId\":\"" + excel + "\",\"minimumLevel\":2},"
                        + "{\"skillId\":\"" + excel + "\",\"minimumLevel\":3}]}", "requiredSkills[1].skillId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/hr/job-roles", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/hr/job-roles", "{\"title\":\"Account executive\"}").andExpect(status().isCreated());
        owner.post("/api/v1/hr/job-roles", "{\"title\":\"ACCOUNT EXECUTIVE\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("title"));
    }

    @Test
    void archivedSkillsAndDepartmentsCantBeNewlyChosen() throws Exception {
        UUID id = TestHr.jobRole(owner, "Analyst", sales, Map.of(excel, 2));
        owner.post("/api/v1/hr/skills/" + excel + "/archive", "").andExpect(status().isOk());
        owner.post("/api/v1/hr/departments/" + sales + "/archive", "").andExpect(status().isOk());
        // unchanged references are kept
        owner.put("/api/v1/hr/job-roles/" + id, "{\"title\":\"Senior analyst\",\"departmentId\":\"" + sales
                + "\",\"requiredSkills\":[{\"skillId\":\"" + excel + "\",\"minimumLevel\":3}],\"version\":0}")
                .andExpect(status().isOk());
        // new ones are refused
        owner.post("/api/v1/hr/job-roles", "{\"title\":\"Clerk\",\"requiredSkills\":[{\"skillId\":\"" + excel
                + "\",\"minimumLevel\":1}]}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This record is archived."));
        owner.post("/api/v1/hr/job-roles", "{\"title\":\"Clerk\",\"departmentId\":\"" + sales + "\"}")
                .andExpect(status().isConflict());
    }
}
```

Add `"departments", "skills", "job_roles", "job_role_skills"` to `RlsCoverageIT.EXPECTED_TENANT_TABLES`.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*SkillApiIT' --tests '*DepartmentApiIT' --tests '*JobRoleApiIT' --tests '*RlsCoverageIT'`
Expected: FAIL — 404s (no routes) and the RLS coverage mismatch (tables don't exist).

- [ ] **Step 3: Migration**

`backend/src/main/resources/db/migration/V28__hr_base.sql`:

```sql
-- HRMS (Phase 8): module permissions (D20), skills (D7), departments (D5) and job roles with required skills (D6).
INSERT INTO permissions (code, module_code, description) VALUES
    ('hr.settings.manage', 'HRMS', 'Manage departments, job roles, skills, leave types, holidays and onboarding templates'),
    ('hr.leave.approve',   'HRMS', 'See all leave requests, request leave for others, approve, reject and cancel'),
    ('hr.training.manage', 'HRMS', 'Manage courses and assign, complete and cancel training'),
    ('hr.policy.manage',   'HRMS', 'Write, publish and archive HR policies'),
    ('hr.self.service',    'HRMS', 'See your own HR record, request leave and read published policies');
UPDATE permissions SET description = 'View employees, departments, job roles, skills and the HR dashboard'
WHERE code = 'hr.employee.read';
UPDATE permissions SET description = 'Hire, edit and end employment, set skills, start onboarding, adjust leave'
WHERE code = 'hr.employee.update';
SELECT grant_to_system_roles(ARRAY['hr.employee.read', 'hr.employee.update', 'hr.settings.manage',
                                   'hr.leave.approve', 'hr.training.manage', 'hr.policy.manage', 'hr.self.service']);

CREATE TABLE skills (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name         text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 60),
    name_key     text NOT NULL,
    archived_at  timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name_key)
);
ALTER TABLE skills ENABLE ROW LEVEL SECURITY;
ALTER TABLE skills FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON skills
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE departments (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name         text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 80),
    name_key     text NOT NULL,
    description  text CHECK (description IS NULL OR length(description) <= 500),
    archived_at  timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name_key)
);
ALTER TABLE departments ENABLE ROW LEVEL SECURITY;
ALTER TABLE departments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON departments
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE job_roles (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    title          text NOT NULL CHECK (title = btrim(title) AND length(title) BETWEEN 1 AND 80),
    title_key      text NOT NULL,
    department_id  uuid,
    description    text CHECK (description IS NULL OR length(description) <= 2000),
    archived_at    timestamptz,
    created_at     timestamptz NOT NULL,
    updated_at     timestamptz NOT NULL,
    version        bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, title_key),
    FOREIGN KEY (tenant_id, department_id) REFERENCES departments (tenant_id, id)
);
CREATE INDEX job_roles_department_idx ON job_roles (tenant_id, department_id);
ALTER TABLE job_roles ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_roles FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON job_roles
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE job_role_skills (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    job_role_id    uuid NOT NULL,
    skill_id       uuid NOT NULL,
    minimum_level  integer NOT NULL CHECK (minimum_level BETWEEN 1 AND 5),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, job_role_id, skill_id),
    FOREIGN KEY (tenant_id, job_role_id) REFERENCES job_roles (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, skill_id) REFERENCES skills (tenant_id, id)
);
ALTER TABLE job_role_skills ENABLE ROW LEVEL SECURITY;
ALTER TABLE job_role_skills FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON job_role_skills
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```

- [ ] **Step 4: Shared helpers, permissions and module marker**

`hrms/package-info.java`:

```java
/**
 * HRMS (Phase 8, ADR-0013): employees as directory persons with an employment record, departments, job roles with
 * required skills, onboarding, leave, training, policies and self-service. Every permission belongs to module HRMS.
 */
package com.nexusops.hrms;
```

`hrms/HrPermissions.java`:

```java
package com.nexusops.hrms;

/** HRMS permission codes (D20); all belong to module HRMS. */
public final class HrPermissions {

    public static final String EMPLOYEE_READ = "hr.employee.read";
    public static final String EMPLOYEE_UPDATE = "hr.employee.update";
    public static final String SETTINGS_MANAGE = "hr.settings.manage";
    public static final String LEAVE_APPROVE = "hr.leave.approve";
    public static final String TRAINING_MANAGE = "hr.training.manage";
    public static final String POLICY_MANAGE = "hr.policy.manage";
    public static final String SELF_SERVICE = "hr.self.service";

    private HrPermissions() {}
}
```

`hrms/HrRecords.java`:

```java
package com.nexusops.hrms;

import com.nexusops.shared.web.ApiProblem;
import java.util.Locale;

/** Shared messages and checks for HRMS records (D22). */
final class HrRecords {

    static final String NOT_FOUND = "Record not found.";
    static final String STALE = "This record was changed by someone else. Reload and try again.";
    static final String ARCHIVED = "This record is archived.";
    static final String FORBIDDEN = "You do not have permission to perform this action.";

    private HrRecords() {}

    static void checkVersion(long current, Long requested) {
        if (requested == null) {
            throw ApiProblem.badRequestField("version", "Reload the record and try again.");
        }
        if (current != requested) {
            throw ApiProblem.conflict(STALE);
        }
    }

    static void requireNotArchived(boolean archived) {
        if (archived) {
            throw ApiProblem.conflict(ARCHIVED);
        }
    }

    /** Trimmed, inner whitespace collapsed (names and titles). */
    static String collapse(String value) {
        return value.replaceAll("\\s+", " ");
    }

    static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
```

- [ ] **Step 5: Skills**

`hrms/domain/Skill.java`:

```java
package com.nexusops.hrms.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "skills")
public class Skill extends TenantOwnedEntity {

    @Column(nullable = false)
    private String name;

    @Column(name = "name_key", nullable = false)
    private String nameKey;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Skill() {}

    public Skill(UUID id, String name, String nameKey) {
        super(id);
        this.createdAt = Instant.now();
        rename(name, nameKey);
    }

    public void rename(String newName, String newKey) {
        this.name = newName;
        this.nameKey = newKey;
        this.updatedAt = Instant.now();
    }

    public void archive(Instant now) {
        this.archivedAt = now;
        this.updatedAt = now;
    }

    public void restore() {
        this.archivedAt = null;
        this.updatedAt = Instant.now();
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public String getName() {
        return name;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getVersion() {
        return version;
    }
}
```

`hrms/domain/SkillRepository.java`:

```java
package com.nexusops.hrms.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SkillRepository extends JpaRepository<Skill, UUID> {

    List<Skill> findByArchivedAtIsNullOrderByNameAsc();

    List<Skill> findByArchivedAtIsNotNullOrderByNameAsc();

    boolean existsByNameKey(String nameKey);

    boolean existsByNameKeyAndIdNot(String nameKey, UUID id);
}
```

`hrms/SkillRef.java` and `hrms/SkillView.java`:

```java
package com.nexusops.hrms;

import java.util.UUID;

public record SkillRef(UUID id, String name) {}
```

```java
package com.nexusops.hrms;

import java.time.Instant;
import java.util.UUID;

public record SkillView(UUID id, String name, Instant archivedAt, long version) {}
```

`hrms/SkillService.java`:

```java
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
```

- [ ] **Step 6: Departments**

`hrms/domain/Department.java` mirrors `Skill.java` with these differences:
- table `departments`;
- an extra nullable `description` column (`private String description;`);
- constructor `Department(UUID id, String name, String nameKey, String description)`;
- the mutator is `apply(String newName, String newKey, String newDescription)` instead of `rename`;
- getters `getName()`, `getDescription()`, `getArchivedAt()`, `getVersion()`, `isArchived()`.

`hrms/domain/DepartmentRepository.java` has the same four finders as `SkillRepository`, typed `Department`.

```java
package com.nexusops.hrms;

import java.util.UUID;

public record DepartmentRef(UUID id, String name) {}
```

```java
package com.nexusops.hrms;

public record DepartmentCommand(String name, String description) {}
```

```java
package com.nexusops.hrms;

import java.time.Instant;
import java.util.UUID;

public record DepartmentView(UUID id, String name, String description, Instant archivedAt, long version) {}
```

`hrms/DepartmentService.java` mirrors `SkillService.java`. Its responsibilities, which `DepartmentApiIT` asserts:
- **Messages:**
  - `UNKNOWN = "Choose a department in this workspace."`
  - `NAME_TAKEN = "Another department already uses this name."` (409, field `name`)
- **Lock:** `"hr-departments"`.
- **Validation:**
  - name: `Text.required(raw, 80, "name")` collapsed;
  - description: `Text.optional(raw, 500, "description")`.
- **`update(id, command, version)` order:**
  1. find (404);
  2. `checkVersion`;
  3. validate name and description;
  4. `requireNotArchived`;
  5. name uniqueness (409);
  6. apply, flush, audit.
- **Audit actions:**
  - `DepartmentCreated` (after: name, description);
  - `DepartmentUpdated` (before/after);
  - `DepartmentArchived`;
  - `DepartmentRestored`.
- **Public methods:**
  - `list(boolean archived)` sorted by name;
  - `create(DepartmentCommand)`;
  - `update(UUID, DepartmentCommand, Long)`;
  - `archive(UUID)` (idempotent; Task 2 adds the "employees still in it" refusal);
  - `restore(UUID)`.
- **Package-private helpers:**
  - `Department resolve(UUID id, String field)` (unknown or null → 400 `UNKNOWN` on `field`);
  - `Map<UUID, Department> byIds(Collection<UUID>)`;
  - `static DepartmentRef ref(Department)`.

- [ ] **Step 7: Job roles with required skills**

`hrms/domain/JobRole.java`:

```java
package com.nexusops.hrms.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "job_roles")
public class JobRole extends TenantOwnedEntity {

    @Column(nullable = false)
    private String title;

    @Column(name = "title_key", nullable = false)
    private String titleKey;

    @Column(name = "department_id")
    private UUID departmentId;

    private String description;

    @Column(name = "archived_at")
    private Instant archivedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected JobRole() {}

    public JobRole(UUID id, String title, String titleKey, UUID departmentId, String description) {
        super(id);
        this.createdAt = Instant.now();
        apply(title, titleKey, departmentId, description);
    }

    public void apply(String newTitle, String newKey, UUID newDepartmentId, String newDescription) {
        this.title = newTitle;
        this.titleKey = newKey;
        this.departmentId = newDepartmentId;
        this.description = newDescription;
        this.updatedAt = Instant.now();
    }

    public void archive(Instant now) {
        this.archivedAt = now;
        this.updatedAt = now;
    }

    public void restore() {
        this.archivedAt = null;
        this.updatedAt = Instant.now();
    }

    public boolean isArchived() {
        return archivedAt != null;
    }

    public String getTitle() {
        return title;
    }

    public UUID getDepartmentId() {
        return departmentId;
    }

    public String getDescription() {
        return description;
    }

    public Instant getArchivedAt() {
        return archivedAt;
    }

    public long getVersion() {
        return version;
    }
}
```

`hrms/domain/JobRoleRepository.java`:

```java
package com.nexusops.hrms.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface JobRoleRepository extends JpaRepository<JobRole, UUID> {

    List<JobRole> findByArchivedAtIsNullOrderByTitleAsc();

    List<JobRole> findByArchivedAtIsNotNullOrderByTitleAsc();

    boolean existsByTitleKey(String titleKey);

    boolean existsByTitleKeyAndIdNot(String titleKey, UUID id);
}
```

`hrms/domain/JobRoleSkill.java`:

```java
package com.nexusops.hrms.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.UUID;

/** One required skill of a job role (D6); replaced as a set when the role is edited. */
@Entity
@Table(name = "job_role_skills")
public class JobRoleSkill extends TenantOwnedEntity {

    @Column(name = "job_role_id", nullable = false, updatable = false)
    private UUID jobRoleId;

    @Column(name = "skill_id", nullable = false, updatable = false)
    private UUID skillId;

    @Column(name = "minimum_level", nullable = false, updatable = false)
    private int minimumLevel;

    protected JobRoleSkill() {}

    public JobRoleSkill(UUID id, UUID jobRoleId, UUID skillId, int minimumLevel) {
        super(id);
        this.jobRoleId = jobRoleId;
        this.skillId = skillId;
        this.minimumLevel = minimumLevel;
    }

    public UUID getJobRoleId() {
        return jobRoleId;
    }

    public UUID getSkillId() {
        return skillId;
    }

    public int getMinimumLevel() {
        return minimumLevel;
    }
}
```

`hrms/domain/JobRoleSkillRepository.java`:

```java
package com.nexusops.hrms.domain;

import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface JobRoleSkillRepository extends JpaRepository<JobRoleSkill, UUID> {

    List<JobRoleSkill> findByJobRoleIdIn(Collection<UUID> jobRoleIds);

    List<JobRoleSkill> findByJobRoleId(UUID jobRoleId);

    @Modifying
    @Query("delete from JobRoleSkill s where s.jobRoleId = :jobRoleId")
    void deleteByJobRole(UUID jobRoleId);
}
```

Records:

```java
package com.nexusops.hrms;

import java.util.UUID;

/** Raw input: one required skill of a job role. */
public record RequiredSkill(UUID skillId, Integer minimumLevel) {}
```

```java
package com.nexusops.hrms;

import java.util.UUID;

public record RequiredSkillView(UUID skillId, String name, int minimumLevel) {}
```

```java
package com.nexusops.hrms;

import java.util.UUID;

public record JobRoleRef(UUID id, String title) {}
```

```java
package com.nexusops.hrms;

import java.util.List;
import java.util.UUID;

public record JobRoleCommand(String title, UUID departmentId, String description, List<RequiredSkill> requiredSkills) {}
```

```java
package com.nexusops.hrms;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** {@code requiredSkills} sorted by skill name. */
public record JobRoleView(UUID id, String title, DepartmentRef department, String description,
        List<RequiredSkillView> requiredSkills, Instant archivedAt, long version) {}
```

`hrms/JobRoleService.java`:

```java
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
```

`JobRoleApiIT.requiredSkillsAreValidated` hits the field checks in this order. The missing-level case `{"skillId":…}` is reported on `minimumLevel`, the repeat on index 1, and the unknown department on `departmentId` before any skill lookup. A test case lists one failing field at a time, so the order between kinds doesn't matter beyond what each case isolates.

- [ ] **Step 8: Web layer**

`hrms/web/HrDtos.java`:

```java
package com.nexusops.hrms.web;

import com.nexusops.hrms.DepartmentCommand;
import com.nexusops.hrms.JobRoleCommand;
import com.nexusops.hrms.RequiredSkill;
import java.util.List;
import java.util.UUID;

/** Request bodies of the HRMS API; later tasks add theirs here. */
final class HrDtos {

    private HrDtos() {}

    record NameRequest(String name, Long version) {}

    record DepartmentRequest(String name, String description, Long version) {
        DepartmentCommand command() {
            return new DepartmentCommand(name, description);
        }
    }

    record JobRoleRequest(String title, UUID departmentId, String description, List<RequiredSkill> requiredSkills,
            Long version) {
        JobRoleCommand command() {
            return new JobRoleCommand(title, departmentId, description, requiredSkills);
        }
    }

    record VersionRequest(Long version) {}
}
```

`hrms/web/SkillController.java`:

```java
package com.nexusops.hrms.web;

import com.nexusops.hrms.SkillService;
import com.nexusops.hrms.SkillView;
import com.nexusops.hrms.web.HrDtos.NameRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/skills")
class SkillController {

    private final SkillService skills;

    SkillController(SkillService skills) {
        this.skills = skills;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('hr.employee.read')")
    List<SkillView> list(@RequestParam(defaultValue = "false") boolean archived) {
        return skills.list(archived);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    SkillView create(@RequestBody NameRequest request) {
        return skills.create(request.name());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    SkillView update(@PathVariable UUID id, @RequestBody NameRequest request) {
        return skills.update(id, request.name(), request.version());
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    SkillView archive(@PathVariable UUID id) {
        return skills.archive(id);
    }

    @PostMapping("/{id}/restore")
    @PreAuthorize("hasAuthority('hr.settings.manage')")
    SkillView restore(@PathVariable UUID id) {
        return skills.restore(id);
    }
}
```

`hrms/web/DepartmentController.java` has the same shape at `/api/v1/hr/departments`:
- `DepartmentView`;
- create takes `DepartmentRequest.command()`, and update takes `(id, request.command(), request.version())`;
- the same `@PreAuthorize` values: `hr.employee.read` on GET, `hr.settings.manage` on writes.

`hrms/web/JobRoleController.java` is at `/api/v1/hr/job-roles`:
- `GET` with `archived` (default false) and an optional `departmentId` → `list(archived, departmentId)`;
- `GET /{id}` → `get(id)`;
- POST, PUT, archive and restore, as in `SkillController`, using `JobRoleRequest`.

All GETs take `hr.employee.read` and all writes `hr.settings.manage`.

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*SkillApiIT' --tests '*DepartmentApiIT' --tests '*JobRoleApiIT' --tests '*RlsCoverageIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest'`
Expected: PASS.

- [ ] **Step 10: Full suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src
git commit -m "feat(hrms): module permissions, skills, departments and job roles with required skills"
```

---

### Task 2: Employees — hire, edit, end and reinstate employment, the directory role in step, skills and skill gaps

**Files:**
- Create: `backend/src/main/resources/db/migration/V29__employees.sql`
- Create (directory): `backend/src/main/java/com/nexusops/directory/EmploymentGuard.java`; Modify: `directory/PartyService.java` (`recordEmployment`, guards in `setRole`)
- Create (hrms): `HrClock.java`, `EmploymentType.java`, `EmploymentStatus.java`, `ManagerChain.java`, `SkillGaps.java`, `NewPerson.java`, `EmployeeCommand.java`, `EmployeeUpdate.java`, `EndEmployment.java`, `EmployeeSkillCommand.java`, `PersonRef.java`, `EmployeeRef.java`, `EmployeeSkillView.java`, `OnboardingProgress.java`, `CourseRef.java`, `EmployeeView.java`, `EmployeeSummary.java`, `EmployeeQuery.java`, `SkillGapView.java`, `EmploymentEndedEvent.java`, `EmployeeService.java`, `HrQueries.java`, `EmployeeSubjects.java`, `EmployeeRelations.java`, `HrmsEmploymentGuard.java`
- Create: `hrms/domain/Employee.java`, `EmployeeRepository.java`, `EmployeeSkill.java`, `EmployeeSkillRepository.java`; `hrms/web/EmployeeController.java`; Modify: `hrms/web/HrDtos.java`, `hrms/DepartmentService.java` (archive refusal)
- Test: `backend/src/test/java/com/nexusops/hrms/EmploymentStatusTest.java`, `ManagerChainTest.java`, `SkillGapsTest.java`, `EmployeeApiIT.java`; Modify: `support/TestHr.java` (`hire`), `RlsCoverageIT.java` (`employees`, `employee_skills`)

**Interfaces:**
- Consumes (Task 1):
  - `SkillService.resolve/byIds/ref`, `DepartmentService.resolve/byIds/ref`, `JobRoleService.resolve/byIds/ref/requiredLevels`;
  - `HrRecords`, `HrPermissions`, `TestHr`.
- Produces:
  - **Directory:**
    - `directory.EmploymentGuard` (`void beforeEmployeeRoleChange(UUID personId)`).
    - `PartyService.recordEmployment(UUID personId, String employeeNumber, LocalDate since, boolean active)`.
  - **Time and status:**
    - `HrClock` (`LocalDate today()`, `ZoneId zone()`).
    - `EmploymentStatus {UPCOMING, ACTIVE, LEFT}` with `static EmploymentStatus of(LocalDate start, LocalDate end, LocalDate today)`.
    - `EmploymentType {FULL_TIME, PART_TIME, CONTRACT, INTERN}`.
  - **Pure rules:**
    - `ManagerChain.createsLoop(UUID employeeId, UUID managerId, Map<UUID, UUID> managerOf)`.
    - `SkillGaps.gaps(Map<UUID,Integer> required, Map<UUID,Integer> held) → List<SkillGaps.Gap>` with `record Gap(UUID skillId, int required, Integer held)`.
  - **Records:**
    - `PersonRef(UUID id, String name, String email, String phone)`
    - `EmployeeRef(UUID id, String name, String employeeNumber)`
    - `EmployeeSkillView(UUID skillId, String name, int level, String note)`
    - `OnboardingProgress(UUID templateId, String templateName, int done, int total)`
    - `CourseRef(UUID id, String title)`
    - `EmployeeView(UUID id, PersonRef person, String employeeNumber, DepartmentRef department, JobRoleRef jobRole, EmployeeRef manager, EmploymentType employmentType, LocalDate startDate, LocalDate endDate, String endReason, EmploymentStatus status, MemberRef user, List<EmployeeSkillView> skills, OnboardingProgress onboarding, Instant createdAt, Instant updatedAt, long version)`. `onboarding` is null until Task 3.
    - `EmployeeSummary(UUID id, String name, String employeeNumber, DepartmentRef department, JobRoleRef jobRole, EmployeeRef manager, EmploymentStatus status, LocalDate startDate, LocalDate endDate)`
    - `EmployeeQuery(String q, EmploymentStatus status, UUID departmentId, UUID jobRoleId, UUID managerId, UUID personId)`. A null `status` means everyone not `LEFT`, except when `personId` is given: then every status matches, so a person's record is found even after they left.
    - `SkillGapView(EmployeeRef employee, JobRoleRef jobRole, List<SkillGapView.Gap> gaps)` with `record Gap(SkillRef skill, int required, Integer held, List<CourseRef> recommendedCourses)`.
    - `EmploymentEndedEvent(UUID employeeId, LocalDate endDate)`.
  - **`EmployeeService`:**
    - Public:
      - `list(EmployeeQuery, Integer page, Integer size) → PageResponse<EmployeeSummary>`;
      - `get(UUID)`, `hire(EmployeeCommand)`, `update(UUID, EmployeeUpdate, Long version)`;
      - `end(UUID, EndEmployment, Long version)`, `reinstate(UUID, Long version)`;
      - `setSkills(UUID, List<EmployeeSkillCommand>, Long version)`, `skillGap(UUID)`;
      - `members(String q) → List<AssigneeView>` (active team members to link, first 20; `com.nexusops.collaboration.AssigneeView`).
    - Package-private:
      - `Employee find(UUID)` (404);
      - `Employee resolve(UUID id, String field)` (400 `"Choose an employee in this workspace."`);
      - `Map<UUID, Employee> byIds(Collection<UUID>)`;
      - `Optional<Employee> forUser(UUID userId)`;
      - `Map<UUID, String> names(Collection<Employee>)` (person names);
      - `EmployeeRef ref(Employee, Map<UUID,String> names)`;
      - `Map<UUID,Integer> heldLevels(UUID employeeId)`;
      - `void raiseSkills(UUID employeeId, Map<UUID,Integer> levels)` (keeps the higher level; used by Task 5).
  - **Extension hooks** in `EmployeeService` that later tasks fill:
    - a `List<EmployeeHireHook>` (Task 3), via the interface `EmployeeHireHook { void afterHire(Employee employee, EmployeeCommand command); }`;
    - `OnboardingProgress onboarding(UUID employeeId)` via an optional `List<OnboardingReader>` (Task 3), via the interface `OnboardingReader { OnboardingProgress progress(UUID employeeId); }`;
    - `List<CourseRef> recommended(UUID skillId, int level)` via an optional `List<CourseFinder>` (Task 5), via the interface `CourseFinder { List<CourseRef> teaching(UUID skillId, int minimumLevel); }`.
  - `TestHr.hire(Api owner, String firstName, String lastName, String number) → UUID` (new person, no extras).

- [ ] **Step 1: Write the failing unit tests**

`backend/src/test/java/com/nexusops/hrms/EmploymentStatusTest.java`:

```java
package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class EmploymentStatusTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 9);

    @Test
    void upcomingUntilTheStartDate() {
        assertThat(EmploymentStatus.of(TODAY.plusDays(1), null, TODAY)).isEqualTo(EmploymentStatus.UPCOMING);
        assertThat(EmploymentStatus.of(TODAY, null, TODAY)).isEqualTo(EmploymentStatus.ACTIVE);
    }

    @Test
    void activeThroughTheLastDayThenLeft() {
        assertThat(EmploymentStatus.of(TODAY.minusYears(1), TODAY, TODAY)).isEqualTo(EmploymentStatus.ACTIVE);
        assertThat(EmploymentStatus.of(TODAY.minusYears(1), TODAY.minusDays(1), TODAY)).isEqualTo(EmploymentStatus.LEFT);
        assertThat(EmploymentStatus.of(TODAY.minusYears(1), TODAY.plusDays(30), TODAY)).isEqualTo(EmploymentStatus.ACTIVE);
    }

    @Test
    void endedBeforeItStartedIsLeft() {
        assertThat(EmploymentStatus.of(TODAY.plusDays(5), TODAY.minusDays(1), TODAY)).isEqualTo(EmploymentStatus.LEFT);
    }
}
```

`backend/src/test/java/com/nexusops/hrms/ManagerChainTest.java`:

```java
package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ManagerChainTest {

    final UUID a = UUID.randomUUID();
    final UUID b = UUID.randomUUID();
    final UUID c = UUID.randomUUID();

    @Test
    void nobodyManagesThemselves() {
        assertThat(ManagerChain.createsLoop(a, a, Map.of())).isTrue();
    }

    @Test
    void aLoopThroughSeveralLevelsIsFound() {
        Map<UUID, UUID> managerOf = new HashMap<>();
        managerOf.put(a, b); // a reports to b
        managerOf.put(b, c); // b reports to c
        assertThat(ManagerChain.createsLoop(c, a, managerOf)).isTrue(); // c reporting to a closes the loop
        assertThat(ManagerChain.createsLoop(c, b, managerOf)).isTrue();
    }

    @Test
    void anOrdinaryChainIsFine() {
        Map<UUID, UUID> managerOf = Map.of(a, b);
        assertThat(ManagerChain.createsLoop(c, a, managerOf)).isFalse();
        assertThat(ManagerChain.createsLoop(a, c, managerOf)).isFalse();
        assertThat(ManagerChain.createsLoop(a, null, managerOf)).isFalse();
    }

    @Test
    void anExistingLoopInTheDataDoesNotHangTheCheck() {
        Map<UUID, UUID> broken = Map.of(a, b, b, a);
        assertThat(ManagerChain.createsLoop(c, a, broken)).isFalse();
    }
}
```

`backend/src/test/java/com/nexusops/hrms/SkillGapsTest.java`:

```java
package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SkillGapsTest {

    final UUID negotiation = UUID.randomUUID();
    final UUID excel = UUID.randomUUID();
    final UUID crm = UUID.randomUUID();

    @Test
    void missingAndBelowLevelSkillsAreGapsInTheRolesOrder() {
        Map<UUID, Integer> required = new LinkedHashMap<>();
        required.put(negotiation, 4);
        required.put(excel, 2);
        required.put(crm, 3);
        Map<UUID, Integer> held = Map.of(negotiation, 2, excel, 5);
        assertThat(SkillGaps.gaps(required, held)).containsExactly(
                new SkillGaps.Gap(negotiation, 4, 2),
                new SkillGaps.Gap(crm, 3, null));
    }

    @Test
    void meetingEveryRequirementLeavesNoGaps() {
        assertThat(SkillGaps.gaps(Map.of(excel, 3), Map.of(excel, 3))).isEmpty();
        assertThat(SkillGaps.gaps(Map.of(), Map.of(excel, 1))).isEmpty();
    }
}
```

Run: `cd backend && ./gradlew test --tests '*EmploymentStatusTest' --tests '*ManagerChainTest' --tests '*SkillGapsTest'` — Expected: FAIL (types don't exist).

- [ ] **Step 2: The pure rules**

```java
package com.nexusops.hrms;

public enum EmploymentType {
    FULL_TIME, PART_TIME, CONTRACT, INTERN
}
```

```java
package com.nexusops.hrms;

import java.time.LocalDate;

/** Derived from the employment dates in the workspace's time zone (D2). The end date is the last working day. */
public enum EmploymentStatus {
    UPCOMING, ACTIVE, LEFT;

    public static EmploymentStatus of(LocalDate start, LocalDate end, LocalDate today) {
        if (end != null && end.isBefore(today)) {
            return LEFT;
        }
        return start.isAfter(today) ? UPCOMING : ACTIVE;
    }
}
```

```java
package com.nexusops.hrms;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Reporting lines must not loop (D2): nobody may end up, directly or through others, managing themselves. */
final class ManagerChain {

    private ManagerChain() {}

    /** True when making {@code managerId} the manager of {@code employeeId} would close a loop. */
    static boolean createsLoop(UUID employeeId, UUID managerId, Map<UUID, UUID> managerOf) {
        Set<UUID> seen = new HashSet<>();
        UUID current = managerId;
        while (current != null && seen.add(current)) {
            if (current.equals(employeeId)) {
                return true;
            }
            current = managerOf.get(current);
        }
        return false;
    }
}
```

```java
package com.nexusops.hrms;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The deterministic skill-gap rule (D8): a required skill held below its minimum level, or not at all. */
final class SkillGaps {

    record Gap(UUID skillId, int required, Integer held) {}

    private SkillGaps() {}

    static List<Gap> gaps(Map<UUID, Integer> required, Map<UUID, Integer> held) {
        List<Gap> gaps = new ArrayList<>();
        required.forEach((skillId, minimum) -> {
            Integer level = held.get(skillId);
            if (level == null || level < minimum) {
                gaps.add(new Gap(skillId, minimum, level));
            }
        });
        return gaps;
    }
}
```

Run the three unit tests — Expected: PASS.

- [ ] **Step 3: Write the failing integration test**

Add to `support/TestHr.java`:

```java
    public static UUID hire(Api owner, String firstName, String lastName, String number) throws Exception {
        return Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"" + firstName
                + "\",\"lastName\":\"" + lastName + "\"},\"employeeNumber\":\"" + number
                + "\",\"startDate\":\"2026-01-05\"}").andExpect(status().isCreated()));
    }
```

`backend/src/test/java/com/nexusops/hrms/EmployeeApiIT.java`:

```java
package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class EmployeeApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID sales;
    UUID negotiation;
    UUID excel;
    UUID executive;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hremp"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
        sales = TestHr.department(owner, "Sales");
        negotiation = TestHr.skill(owner, "Negotiation");
        excel = TestHr.skill(owner, "Excel");
        executive = TestHr.jobRole(owner, "Account executive", sales, Map.of(negotiation, 4, excel, 2));
    }

    private UUID ownerUserId() {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users where email = ?", UUID.class,
                ws.email());
    }

    @Test
    void hiringAnExistingPersonMakesThemAnEmployeeInTheDirectory() throws Exception {
        UUID person = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Meera\",\"lastName\":\"Iyer\","
                + "\"email\":\"meera@deccan.test\"}").andExpect(status().isCreated()));
        UUID id = Api.id(owner.post("/api/v1/hr/employees", "{\"personId\":\"" + person + "\",\"employeeNumber\":\"E-7\","
                        + "\"departmentId\":\"" + sales + "\",\"jobRoleId\":\"" + executive + "\",\"employmentType\":"
                        + "\"FULL_TIME\",\"startDate\":\"2026-01-05\",\"userId\":\"" + ownerUserId() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.person.name").value("Meera Iyer"))
                .andExpect(jsonPath("$.person.email").value("meera@deccan.test"))
                .andExpect(jsonPath("$.department.name").value("Sales"))
                .andExpect(jsonPath("$.jobRole.title").value("Account executive"))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.user.id").value(ownerUserId().toString())));
        owner.get("/api/v1/parties/" + person)
                .andExpect(jsonPath("$.roles[?(@.role == 'EMPLOYEE')].status").value(Matchers.contains("ACTIVE")))
                .andExpect(jsonPath("$.roles[?(@.role == 'EMPLOYEE')].employeeNumber").value(Matchers.contains("E-7")))
                .andExpect(jsonPath("$.roles[?(@.role == 'EMPLOYEE')].since").value(Matchers.contains("2026-01-05")));
        // the directory may no longer edit that role by hand
        owner.put("/api/v1/parties/" + person + "/roles/EMPLOYEE", "{\"status\":\"INACTIVE\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This person's employment is managed in HRMS."));
        owner.get("/api/v1/hr/employees/" + id).andExpect(jsonPath("$.employeeNumber").value("E-7"));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'EmployeeHired'", Long.class)).isEqualTo(1);
    }

    @Test
    void hiringANewPersonCreatesThemInTheDirectory() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Ravi\",\"lastName\":\"Kumar\","
                + "\"email\":\"ravi@deccan.test\",\"phone\":\"+91 98\"},\"employeeNumber\":\"E-8\","
                + "\"startDate\":\"2099-01-04\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("UPCOMING"))
                .andExpect(jsonPath("$.employmentType").value("FULL_TIME")));
        String person = Api.read(owner.get("/api/v1/hr/employees/" + id), "$.person.id");
        owner.get("/api/v1/parties/" + person).andExpect(jsonPath("$.kind").value("PERSON"))
                .andExpect(jsonPath("$.phone").value("+91 98"));
    }

    @Test
    void invalidHiresAreFieldErrors() throws Exception {
        UUID org = Api.id(owner.post("/api/v1/organizations", "{\"name\":\"Deccan Retail\"}"));
        String[][] cases = {
                {"{\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\"}", "personId"},
                {"{\"personId\":\"" + org + "\",\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\"}", "personId"},
                {"{\"personId\":\"" + UUID.randomUUID() + "\",\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\"}",
                        "personId"},
                {"{\"person\":{\"firstName\":\"A\",\"lastName\":\"B\"},\"employeeNumber\":\"\",\"startDate\":\"2026-01-05\"}",
                        "employeeNumber"},
                {"{\"person\":{\"firstName\":\"A\",\"lastName\":\"B\"},\"employeeNumber\":\"E-1\"}", "startDate"},
                {"{\"person\":{\"firstName\":\"A\",\"lastName\":\"B\"},\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\","
                        + "\"departmentId\":\"" + UUID.randomUUID() + "\"}", "departmentId"},
                {"{\"person\":{\"firstName\":\"A\",\"lastName\":\"B\"},\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\","
                        + "\"jobRoleId\":\"" + UUID.randomUUID() + "\"}", "jobRoleId"},
                {"{\"person\":{\"firstName\":\"A\",\"lastName\":\"B\"},\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\","
                        + "\"managerId\":\"" + UUID.randomUUID() + "\"}", "managerId"},
                {"{\"person\":{\"firstName\":\"A\",\"lastName\":\"B\"},\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\","
                        + "\"userId\":\"" + UUID.randomUUID() + "\"}", "userId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/hr/employees", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
    }

    @Test
    void personsNumbersAndAccountsBelongToOneEmployeeEach() throws Exception {
        UUID person = Api.id(owner.post("/api/v1/persons", "{\"firstName\":\"Asha\",\"lastName\":\"Rao\"}"));
        owner.post("/api/v1/hr/employees", "{\"personId\":\"" + person + "\",\"employeeNumber\":\"E-1\","
                + "\"startDate\":\"2026-01-05\",\"userId\":\"" + ownerUserId() + "\"}").andExpect(status().isCreated());
        owner.post("/api/v1/hr/employees", "{\"personId\":\"" + person + "\",\"employeeNumber\":\"E-2\","
                + "\"startDate\":\"2026-01-05\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].message").value("This person already has an employee record."));
        owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"B\",\"lastName\":\"C\"},\"employeeNumber\":\"e-1\","
                + "\"startDate\":\"2026-01-05\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("employeeNumber"));
        owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"B\",\"lastName\":\"C\"},\"employeeNumber\":\"E-3\","
                + "\"startDate\":\"2026-01-05\",\"userId\":\"" + ownerUserId() + "\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].message").value("This team member is already linked to an employee."));
    }

    @Test
    void managerLoopsAreRefused() throws Exception {
        UUID a = TestHr.hire(owner, "Ann", "A", "A-1");
        UUID b = TestHr.hire(owner, "Ben", "B", "B-1");
        UUID c = TestHr.hire(owner, "Cy", "C", "C-1");
        owner.put("/api/v1/hr/employees/" + a, update("A-1", b, 0)).andExpect(status().isOk())
                .andExpect(jsonPath("$.manager.name").value("Ben B"));
        owner.put("/api/v1/hr/employees/" + b, update("B-1", c, 0)).andExpect(status().isOk());
        owner.put("/api/v1/hr/employees/" + c, update("C-1", a, 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("managerId"))
                .andExpect(jsonPath("$.errors[0].message").value("A manager can't report to their own report."));
        owner.put("/api/v1/hr/employees/" + c, update("C-1", c, 0)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("managerId"));
        owner.get("/api/v1/hr/employees?managerId=" + c).andExpect(jsonPath("$.items[*].name").value(
                Matchers.contains("Ben B")));
    }

    private static String update(String number, UUID managerId, long version) {
        return "{\"employeeNumber\":\"" + number + "\",\"startDate\":\"2026-01-05\",\"employmentType\":\"FULL_TIME\","
                + "\"managerId\":" + (managerId == null ? "null" : "\"" + managerId + "\"") + ",\"version\":" + version
                + "}";
    }

    @Test
    void endingEmploymentTurnsTheDirectoryRoleOffAndReinstatingTurnsItOn() throws Exception {
        UUID id = TestHr.hire(owner, "Dev", "Patel", "E-9");
        String person = Api.read(owner.get("/api/v1/hr/employees/" + id), "$.person.id");
        String yesterday = LocalDate.now().minusDays(1).toString();
        owner.post("/api/v1/hr/employees/" + id + "/end", "{\"endDate\":\"2025-12-31\",\"version\":0}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("endDate"));
        owner.post("/api/v1/hr/employees/" + id + "/end", "{\"endDate\":\"" + yesterday + "\",\"reason\":\"Resigned\","
                + "\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("LEFT"))
                .andExpect(jsonPath("$.endReason").value("Resigned"));
        owner.get("/api/v1/parties/" + person)
                .andExpect(jsonPath("$.roles[?(@.role == 'EMPLOYEE')].status").value(Matchers.contains("INACTIVE")));
        owner.post("/api/v1/hr/employees/" + id + "/end", "{\"endDate\":\"" + yesterday + "\",\"version\":1}")
                .andExpect(status().isConflict());
        owner.get("/api/v1/hr/employees").andExpect(jsonPath("$.total").value(0));
        owner.get("/api/v1/hr/employees?status=LEFT").andExpect(jsonPath("$.items[*].name").value(
                Matchers.contains("Dev Patel")));
        owner.get("/api/v1/hr/employees?personId=" + person).andExpect(jsonPath("$.items[*].id").value(
                Matchers.contains(id.toString())));
        owner.post("/api/v1/hr/employees/" + id + "/reinstate", "{\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE")).andExpect(jsonPath("$.endDate").doesNotExist());
        owner.get("/api/v1/parties/" + person)
                .andExpect(jsonPath("$.roles[?(@.role == 'EMPLOYEE')].status").value(Matchers.contains("ACTIVE")));
        owner.post("/api/v1/hr/employees/" + id + "/reinstate", "{\"version\":2}").andExpect(status().isConflict());
    }

    @Test
    void listsAndFilters() throws Exception {
        UUID meera = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Meera\",\"lastName\":\"Iyer\"},"
                + "\"employeeNumber\":\"S-1\",\"departmentId\":\"" + sales + "\",\"jobRoleId\":\"" + executive
                + "\",\"startDate\":\"2026-01-05\"}"));
        TestHr.hire(owner, "Arun", "Shah", "O-1");
        owner.get("/api/v1/hr/employees").andExpect(jsonPath("$.items[*].name").value(
                Matchers.contains("Arun Shah", "Meera Iyer")));
        owner.get("/api/v1/hr/employees?departmentId=" + sales).andExpect(jsonPath("$.items[*].id").value(
                Matchers.contains(meera.toString())));
        owner.get("/api/v1/hr/employees?jobRoleId=" + executive).andExpect(jsonPath("$.total").value(1));
        owner.get("/api/v1/hr/employees?q=s-1").andExpect(jsonPath("$.items[*].name").value(Matchers.contains("Meera Iyer")));
        owner.get("/api/v1/hr/employees?q=shah").andExpect(jsonPath("$.items[*].name").value(Matchers.contains("Arun Shah")));
        owner.get("/api/v1/hr/employees?status=UPCOMING").andExpect(jsonPath("$.total").value(0));
    }

    @Test
    void skillsAreSetAsAWholeAndShowTheGapsOfTheJobRole() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Meera\",\"lastName\":\"Iyer\"},"
                + "\"employeeNumber\":\"S-1\",\"jobRoleId\":\"" + executive + "\",\"startDate\":\"2026-01-05\"}"));
        owner.put("/api/v1/hr/employees/" + id + "/skills", "{\"skills\":[{\"skillId\":\"" + negotiation
                        + "\",\"level\":2,\"note\":\"Learning\"},{\"skillId\":\"" + excel + "\",\"level\":5}],\"version\":0}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.skills[*].name").value(Matchers.contains("Excel", "Negotiation")))
                .andExpect(jsonPath("$.skills[1].note").value("Learning"));
        owner.get("/api/v1/hr/employees/" + id + "/skill-gap")
                .andExpect(jsonPath("$.jobRole.title").value("Account executive"))
                .andExpect(jsonPath("$.gaps.length()").value(1))
                .andExpect(jsonPath("$.gaps[0].skill.name").value("Negotiation"))
                .andExpect(jsonPath("$.gaps[0].required").value(4))
                .andExpect(jsonPath("$.gaps[0].held").value(2))
                .andExpect(jsonPath("$.gaps[0].recommendedCourses").isEmpty());
        String[][] cases = {
                {"[{\"skillId\":\"" + UUID.randomUUID() + "\",\"level\":2}]", "skills[0].skillId"},
                {"[{\"skillId\":\"" + excel + "\",\"level\":0}]", "skills[0].level"},
                {"[{\"skillId\":\"" + excel + "\",\"level\":2},{\"skillId\":\"" + excel + "\",\"level\":3}]",
                        "skills[1].skillId"},
                {"[{\"skillId\":\"" + excel + "\",\"level\":2,\"note\":\"" + "x".repeat(201) + "\"}]", "skills[0].note"},
        };
        for (String[] c : cases) {
            owner.put("/api/v1/hr/employees/" + id + "/skills", "{\"skills\":" + c[0] + ",\"version\":1}")
                    .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.put("/api/v1/hr/employees/" + id + "/skills", "{\"skills\":[],\"version\":1}").andExpect(status().isOk())
                .andExpect(jsonPath("$.skills").isEmpty());
        owner.get("/api/v1/hr/employees/" + id + "/skill-gap").andExpect(jsonPath("$.gaps.length()").value(2))
                .andExpect(jsonPath("$.gaps[1].held").doesNotExist());
    }

    @Test
    void anEmployeeWithoutAJobRoleHasNoGaps() throws Exception {
        UUID id = TestHr.hire(owner, "Arun", "Shah", "O-1");
        owner.get("/api/v1/hr/employees/" + id + "/skill-gap").andExpect(jsonPath("$.jobRole").doesNotExist())
                .andExpect(jsonPath("$.gaps").isEmpty());
    }

    @Test
    void aDepartmentWithPeopleInItCantBeArchived() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"M\",\"lastName\":\"I\"},"
                + "\"employeeNumber\":\"S-1\",\"departmentId\":\"" + sales + "\",\"startDate\":\"2026-01-05\"}"));
        owner.post("/api/v1/hr/departments/" + sales + "/archive", "").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Move this department's employees first."));
        owner.post("/api/v1/hr/employees/" + id + "/end", "{\"endDate\":\"" + LocalDate.now().minusDays(1)
                + "\",\"version\":0}").andExpect(status().isOk());
        owner.post("/api/v1/hr/departments/" + sales + "/archive", "").andExpect(status().isOk());
    }

    @Test
    void employeesAreRecordsWithActivityAndAppearOnTheirPersonsTimeline() throws Exception {
        UUID id = TestHr.hire(owner, "Dev", "Patel", "E-9");
        String person = Api.read(owner.get("/api/v1/hr/employees/" + id), "$.person.id");
        owner.post("/api/v1/activities", "{\"subjectType\":\"EMPLOYEE\",\"subjectId\":\"" + id
                + "\",\"type\":\"NOTE\",\"summary\":\"Signed the contract\"}").andExpect(status().isCreated());
        owner.get("/api/v1/activities?subjectType=PARTY&subjectId=" + person + "&includeRelated=true")
                .andExpect(jsonPath("$.items[*].summary").value(Matchers.hasItem("Signed the contract")));
        owner.get("/api/v1/activities?subjectType=EMPLOYEE&subjectId=" + id)
                .andExpect(jsonPath("$.items[0].subject.label").value("Dev Patel · E-9"));
    }

    @Test
    void readersSeeEmployeesButOnlyUpdatersChangeThem() throws Exception {
        UUID id = TestHr.hire(owner, "Dev", "Patel", "E-9");
        UUID role = TestRoles.create(mvc, owner.session(), "HR viewer", "hr.employee.read");
        Api viewer = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        viewer.get("/api/v1/hr/employees/" + id).andExpect(status().isOk());
        viewer.post("/api/v1/hr/employees", "{}").andExpect(status().isForbidden());
        viewer.put("/api/v1/hr/employees/" + id + "/skills", "{\"skills\":[],\"version\":0}")
                .andExpect(status().isForbidden());
        viewer.post("/api/v1/hr/employees/" + id + "/end", "{}").andExpect(status().isForbidden());
    }
}
```

Add `"employees", "employee_skills"` to `RlsCoverageIT.EXPECTED_TENANT_TABLES`.

- [ ] **Step 4: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests '*EmployeeApiIT' --tests '*RlsCoverageIT'`
Expected: FAIL — 404s and the RLS coverage mismatch.

- [ ] **Step 5: Migration**

`backend/src/main/resources/db/migration/V29__employees.sql`:

```sql
-- HRMS employees (D2): the employment record of a directory person, and their skills (D7).
CREATE TABLE employees (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    person_id        uuid NOT NULL,
    employee_number  text NOT NULL CHECK (employee_number = btrim(employee_number)
                                          AND length(employee_number) BETWEEN 1 AND 40),
    number_key       text NOT NULL,
    department_id    uuid,
    job_role_id      uuid,
    manager_id       uuid,
    employment_type  text NOT NULL CHECK (employment_type IN ('FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERN')),
    start_date       date NOT NULL,
    end_date         date,
    end_reason       text CHECK (end_reason IS NULL OR length(end_reason) <= 500),
    user_id          uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at       timestamptz NOT NULL,
    updated_at       timestamptz NOT NULL,
    version          bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, person_id),
    UNIQUE (tenant_id, number_key),
    CHECK (end_date IS NULL OR end_date >= start_date),
    CHECK (end_date IS NOT NULL OR end_reason IS NULL),
    CHECK (manager_id IS NULL OR manager_id <> id),
    FOREIGN KEY (tenant_id, person_id) REFERENCES parties (tenant_id, id),
    FOREIGN KEY (tenant_id, department_id) REFERENCES departments (tenant_id, id),
    FOREIGN KEY (tenant_id, job_role_id) REFERENCES job_roles (tenant_id, id),
    FOREIGN KEY (tenant_id, manager_id) REFERENCES employees (tenant_id, id)
);
CREATE UNIQUE INDEX employees_user_uq ON employees (tenant_id, user_id) WHERE user_id IS NOT NULL;
CREATE INDEX employees_department_idx ON employees (tenant_id, department_id);
CREATE INDEX employees_manager_idx ON employees (tenant_id, manager_id);
ALTER TABLE employees ENABLE ROW LEVEL SECURITY;
ALTER TABLE employees FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employees
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE employee_skills (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    employee_id  uuid NOT NULL,
    skill_id     uuid NOT NULL,
    level        integer NOT NULL CHECK (level BETWEEN 1 AND 5),
    note         text CHECK (note IS NULL OR length(note) <= 200),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, employee_id, skill_id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, skill_id) REFERENCES skills (tenant_id, id)
);
ALTER TABLE employee_skills ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_skills FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_skills
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```

(`parties` has `UNIQUE (tenant_id, id)` from V9, so the composite FK is valid.)

- [ ] **Step 6: The directory side — employment API and guard**

`backend/src/main/java/com/nexusops/directory/EmploymentGuard.java`:

```java
package com.nexusops.directory;

import java.util.UUID;

/**
 * Implemented by a module that owns employment (HRMS, D3): the directory asks before anyone edits a person's EMPLOYEE
 * role by hand, so the role can't drift from the employment record. Throw an ApiProblem to refuse.
 */
public interface EmploymentGuard {

    void beforeEmployeeRoleChange(UUID personId);
}
```

In `PartyService`, add `List<EmploymentGuard> employmentGuards` as the last constructor parameter (Spring injects an empty list when no module provides one). In `setRole`, call the guards right after the permission check:

```java
        if (role == PartyRoleType.EMPLOYEE) {
            employmentGuards.forEach(g -> g.beforeEmployeeRoleChange(id));
        }
```

Add the module API below `ensureRole`:

```java
    /**
     * HRMS's employment rule (D3): makes the person's EMPLOYEE role match their employment record. Not a user action,
     * so no permission check here; the employee number must still be unique among employees.
     */
    @Transactional
    public void recordEmployment(UUID personId, String employeeNumber, LocalDate since, boolean active) {
        Party party = find(personId);
        if (party.getKind() != PartyKind.PERSON) {
            throw new IllegalArgumentException("Only a person can be an employee");
        }
        locks.lock(ROLES_LOCK);
        if (roles.employeeNumberTaken(employeeNumber, personId)) {
            throw ApiProblem.conflictField("employeeNumber", EMPLOYEE_NUMBER_TAKEN);
        }
        PartyRole row = roles.findByPartyIdAndRole(personId, PartyRoleType.EMPLOYEE).orElse(null);
        Map<String, Object> before = row == null ? null : roleSnapshot(row);
        if (row == null) {
            row = new PartyRole(Ids.newId(), personId, PartyRoleType.EMPLOYEE);
        }
        row.update(active ? RoleStatus.ACTIVE : RoleStatus.INACTIVE, since, employeeNumber);
        roles.saveAndFlush(row);
        audit.record(AuditEntry.of("PartyRoleChanged", "Party", personId).withBefore(before)
                .withAfter(roleSnapshot(row)));
    }
```

(imports `java.time.LocalDate` if missing.) `PartyRole.update(RoleStatus, LocalDate, String)` and `roles.employeeNumberTaken(String, UUID)` already exist; `setRole` uses both.

- [ ] **Step 7: HRMS records, clock, domain**

`hrms/HrClock.java`:

```java
package com.nexusops.hrms;

import com.nexusops.tenancy.TenantDirectory;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.springframework.stereotype.Component;

/** "Today" for HR rules: the current date in the workspace's time zone (UTC when it has none or a bad one). */
@Component
public class HrClock {

    private final TenantDirectory tenants;
    private final Clock clock = Clock.systemUTC();

    HrClock(TenantDirectory tenants) {
        this.tenants = tenants;
    }

    public ZoneId zone() {
        try {
            String raw = tenants.currentSettings().timezone();
            return raw == null || raw.isBlank() ? ZoneOffset.UTC : ZoneId.of(raw.strip());
        } catch (DateTimeException e) {
            return ZoneOffset.UTC;
        }
    }

    public LocalDate today() {
        return LocalDate.now(clock.withZone(zone()));
    }
}
```

Records (one file each, package `com.nexusops.hrms`):

```java
public record NewPerson(String firstName, String lastName, String email, String phone) {}
```

```java
/** Raw hire input (D2): {@code personId} or {@code person}, not both. {@code onboardingTemplateId} is used by Task 3. */
public record EmployeeCommand(UUID personId, NewPerson person, String employeeNumber, UUID departmentId,
        UUID jobRoleId, UUID managerId, EmploymentType employmentType, LocalDate startDate, UUID userId,
        UUID onboardingTemplateId) {}
```

```java
public record EmployeeUpdate(String employeeNumber, UUID departmentId, UUID jobRoleId, UUID managerId,
        EmploymentType employmentType, LocalDate startDate, UUID userId) {}
```

```java
public record EndEmployment(LocalDate endDate, String reason) {}
```

```java
public record EmployeeSkillCommand(UUID skillId, Integer level, String note) {}
```

```java
public record PersonRef(UUID id, String name, String email, String phone) {}
```

```java
public record EmployeeRef(UUID id, String name, String employeeNumber) {}
```

```java
public record EmployeeSkillView(UUID skillId, String name, int level, String note) {}
```

```java
/** Onboarding checklist progress (D10); filled in by Task 3. */
public record OnboardingProgress(UUID templateId, String templateName, int done, int total) {}
```

```java
public record CourseRef(UUID id, String title) {}
```

```java
public record EmployeeView(UUID id, PersonRef person, String employeeNumber, DepartmentRef department, JobRoleRef jobRole,
        EmployeeRef manager, EmploymentType employmentType, LocalDate startDate, LocalDate endDate, String endReason,
        EmploymentStatus status, MemberRef user, List<EmployeeSkillView> skills, OnboardingProgress onboarding,
        Instant createdAt, Instant updatedAt, long version) {}
```

```java
public record EmployeeSummary(UUID id, String name, String employeeNumber, DepartmentRef department, JobRoleRef jobRole,
        EmployeeRef manager, EmploymentStatus status, LocalDate startDate, LocalDate endDate) {}
```

```java
/** {@code status} null → everyone who hasn't left (any status when {@code personId} is given). */
public record EmployeeQuery(String q, EmploymentStatus status, UUID departmentId, UUID jobRoleId, UUID managerId,
        UUID personId) {}
```

```java
public record SkillGapView(EmployeeRef employee, JobRoleRef jobRole, List<Gap> gaps) {

    public record Gap(SkillRef skill, int required, Integer held, List<CourseRef> recommendedCourses) {}
}
```

```java
/** Published in the ending transaction; Leave (Task 4) and Training (Task 5) react to it. */
public record EmploymentEndedEvent(UUID employeeId, LocalDate endDate) {}
```

Extension points (each its own file, package-private interfaces in `com.nexusops.hrms`):

```java
interface EmployeeHireHook {
    void afterHire(com.nexusops.hrms.domain.Employee employee, EmployeeCommand command);
}
```

```java
interface OnboardingReader {
    OnboardingProgress progress(UUID employeeId);
}
```

```java
interface CourseFinder {
    List<CourseRef> teaching(UUID skillId, int minimumLevel);
}
```

(`MemberRef` is `com.nexusops.collaboration.MemberRef`.)

`hrms/domain/Employee.java`:

```java
package com.nexusops.hrms.domain;

import com.nexusops.hrms.EmploymentType;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "employees")
public class Employee extends TenantOwnedEntity {

    @Column(name = "person_id", nullable = false, updatable = false)
    private UUID personId;

    @Column(name = "employee_number", nullable = false)
    private String employeeNumber;

    @Column(name = "number_key", nullable = false)
    private String numberKey;

    @Column(name = "department_id")
    private UUID departmentId;

    @Column(name = "job_role_id")
    private UUID jobRoleId;

    @Column(name = "manager_id")
    private UUID managerId;

    @Enumerated(EnumType.STRING)
    @Column(name = "employment_type", nullable = false)
    private EmploymentType employmentType;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "end_reason")
    private String endReason;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Employee() {}

    public Employee(UUID id, UUID personId) {
        super(id);
        this.personId = personId;
        this.createdAt = Instant.now();
    }

    public void apply(String number, String numberKey, UUID departmentId, UUID jobRoleId, UUID managerId,
            EmploymentType type, LocalDate startDate, UUID userId) {
        this.employeeNumber = number;
        this.numberKey = numberKey;
        this.departmentId = departmentId;
        this.jobRoleId = jobRoleId;
        this.managerId = managerId;
        this.employmentType = type;
        this.startDate = startDate;
        this.userId = userId;
        this.updatedAt = Instant.now();
    }

    public void end(LocalDate date, String reason) {
        this.endDate = date;
        this.endReason = reason;
        this.updatedAt = Instant.now();
    }

    public void reinstate() {
        this.endDate = null;
        this.endReason = null;
        this.updatedAt = Instant.now();
    }

    /** Bumps the version for changes kept in child tables (skills). */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID getPersonId() { return personId; }
    public String getEmployeeNumber() { return employeeNumber; }
    public UUID getDepartmentId() { return departmentId; }
    public UUID getJobRoleId() { return jobRoleId; }
    public UUID getManagerId() { return managerId; }
    public EmploymentType getEmploymentType() { return employmentType; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public String getEndReason() { return endReason; }
    public UUID getUserId() { return userId; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
```

`hrms/domain/EmployeeRepository.java`:

```java
package com.nexusops.hrms.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeRepository extends JpaRepository<Employee, UUID>, JpaSpecificationExecutor<Employee> {

    boolean existsByPersonId(UUID personId);

    Optional<Employee> findByPersonId(UUID personId);

    Optional<Employee> findByUserId(UUID userId);

    boolean existsByNumberKey(String numberKey);

    boolean existsByNumberKeyAndIdNot(String numberKey, UUID id);

    boolean existsByUserId(UUID userId);

    boolean existsByUserIdAndIdNot(UUID userId, UUID id);

    /** Everyone in the department who hasn't left by {@code today} (D5). */
    @Query("select count(e) > 0 from Employee e where e.departmentId = :departmentId "
            + "and (e.endDate is null or e.endDate >= :today)")
    boolean anyCurrentIn(UUID departmentId, LocalDate today);

    /** id → manager id, for the loop check (D2). Small per workspace. */
    @Query("select e.id, e.managerId from Employee e where e.managerId is not null")
    List<Object[]> managerPairs();
}
```

`hrms/domain/EmployeeSkill.java` mirrors `JobRoleSkill.java`:
- table `employee_skills`;
- columns `employee_id`, `skill_id`, `level` and a nullable `note`;
- constructor `EmployeeSkill(UUID id, UUID employeeId, UUID skillId, int level, String note)`;
- getters `getEmployeeId()`, `getSkillId()`, `getLevel()`, `getNote()`;
- a mutator `raise(int newLevel)`, used by Task 5, that sets `level` when `newLevel > level`.

Mark only `employee_id` and `skill_id` `updatable = false`.

`hrms/domain/EmployeeSkillRepository.java`:

```java
package com.nexusops.hrms.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeSkillRepository extends JpaRepository<EmployeeSkill, UUID> {

    List<EmployeeSkill> findByEmployeeId(UUID employeeId);

    List<EmployeeSkill> findByEmployeeIdIn(Collection<UUID> employeeIds);

    Optional<EmployeeSkill> findByEmployeeIdAndSkillId(UUID employeeId, UUID skillId);

    @Modifying
    @Query("delete from EmployeeSkill s where s.employeeId = :employeeId")
    void deleteByEmployee(UUID employeeId);
}
```

- [ ] **Step 8: The employee service**

`hrms/EmployeeService.java`:

```java
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
    private static final String LOCK = "hr-employees";

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
        References refs = references(c.departmentId(), c.jobRoleId(), c.managerId(), c.userId(), null);
        PartyBrief existing = null;
        if (c.personId() != null) {
            existing = parties.briefs(List.of(c.personId())).get(c.personId());
            if (existing == null || existing.kind() != PartyKind.PERSON) {
                throw ApiProblem.badRequestField("personId", NOT_A_PERSON);
            }
            HrRecords.requireNotArchived(existing.archived());
        }
        refs.requireNotArchived(null);
        locks.lock(LOCK);
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
        EmploymentType type = c.employmentType() == null ? EmploymentType.FULL_TIME : c.employmentType();
        LocalDate start = required(c.startDate(), "startDate", "Enter a start date.");
        if (employee.getEndDate() != null && employee.getEndDate().isBefore(start)) {
            throw ApiProblem.badRequestField("startDate", END_BEFORE_START);
        }
        References refs = references(c.departmentId(), c.jobRoleId(), c.managerId(), c.userId(), employee);
        refs.requireNotArchived(employee);
        locks.lock(LOCK);
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
```

`PartyService.createPerson` reports its own field names (`firstName`, `email`, …) and its duplicate-person 409; they pass through unchanged. `hire` checks first and last name as `person.firstName` and `person.lastName` before any 409, so the error order holds for the common cases.

- [ ] **Step 9: The list query, subjects, relations, guard, department refusal**

`hrms/HrQueries.java` (Task 7 adds the dashboard here):

```java
package com.nexusops.hrms;

import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

/** SQL read models for HRMS. The status fragments mirror EmploymentStatus — keep the two in step. */
@Component
class HrQueries {

    /** e = employees, :today bound. */
    static final String LEFT = "(e.end_date is not null and e.end_date < :today)";
    static final String UPCOMING = "(not " + LEFT + " and e.start_date > :today)";
    static final String ACTIVE = "(not " + LEFT + " and e.start_date <= :today)";

    record Page(List<UUID> ids, long total) {}

    private final NamedParameterJdbcTemplate jdbc;

    HrQueries(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Page employeePage(EmployeeQuery q, LocalDate today, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", TenantContext.requireTenantId())
                .addValue("today", today);
        StringBuilder where = new StringBuilder("e.tenant_id = :tenant and p.tenant_id = :tenant");
        if (q.status() != null || q.personId() == null) {
            where.append(" and ").append(q.status() == null ? "not " + LEFT : switch (q.status()) {
                case LEFT -> LEFT;
                case UPCOMING -> UPCOMING;
                case ACTIVE -> ACTIVE;
            });
        }
        if (q.personId() != null) {
            where.append(" and e.person_id = :person");
            params.addValue("person", q.personId());
        }
        if (q.departmentId() != null) {
            where.append(" and e.department_id = :department");
            params.addValue("department", q.departmentId());
        }
        if (q.jobRoleId() != null) {
            where.append(" and e.job_role_id = :jobRole");
            params.addValue("jobRole", q.jobRoleId());
        }
        if (q.managerId() != null) {
            where.append(" and e.manager_id = :manager");
            params.addValue("manager", q.managerId());
        }
        String text = Text.optional(q.q(), 100, "q");
        if (text != null) {
            where.append(" and (lower(p.name) like :pattern escape '\\' or e.number_key like :pattern escape '\\')");
            params.addValue("pattern", Text.containsPattern(text));
        }
        String from = " from employees e join parties p on p.id = e.person_id where " + where;
        Long total = jdbc.queryForObject("select count(*)" + from, params, Long.class);
        params.addValue("limit", limit).addValue("offset", offset);
        List<UUID> ids = jdbc.queryForList("select e.id" + from + " order by lower(p.name), e.id limit :limit offset :offset",
                params, UUID.class);
        return new Page(ids, total == null ? 0 : total);
    }

    /** Employee ids whose person's name or number matches the lower-case LIKE pattern (search, D18). */
    List<UUID> searchEmployees(String pattern, int limit) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", TenantContext.requireTenantId())
                .addValue("pattern", pattern).addValue("limit", limit);
        return jdbc.queryForList("select e.id from employees e join parties p on p.id = e.person_id "
                + "where e.tenant_id = :tenant and p.tenant_id = :tenant "
                + "and (lower(p.name) like :pattern escape '\\' or e.number_key like :pattern escape '\\') "
                + "order by lower(p.name), e.id limit :limit", params, UUID.class);
    }
}
```

(`Text.containsPattern` returns a lower-case `%…%` pattern with `\` escapes, as `TicketSubjects.search` uses. `parties.name` is the display name column, 1–200 characters.)

`hrms/EmployeeSubjects.java`:

```java
package com.nexusops.hrms;

import com.nexusops.collaboration.SearchHit;
import com.nexusops.collaboration.SubjectRef;
import com.nexusops.collaboration.SubjectResolver;
import com.nexusops.directory.PartyBrief;
import com.nexusops.directory.PartyService;
import com.nexusops.hrms.domain.Employee;
import com.nexusops.hrms.domain.EmployeeRepository;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Employees as collaboration subjects (D18): label "Name · number", archived once they have left. Reads the
 * repository and PartyService, not EmployeeService, which depends on Subjects indirectly (a cycle otherwise).
 */
@Component
class EmployeeSubjects implements SubjectResolver {

    static final String TYPE = "EMPLOYEE";

    private final EmployeeRepository employees;
    private final PartyService parties;
    private final HrQueries queries;
    private final HrClock clock;

    EmployeeSubjects(EmployeeRepository employees, PartyService parties, HrQueries queries, HrClock clock) {
        this.employees = employees;
        this.parties = parties;
        this.queries = queries;
        this.clock = clock;
    }

    @Override
    public String type() {
        return TYPE;
    }

    @Override
    public String readPermission() {
        return HrPermissions.EMPLOYEE_READ;
    }

    @Override
    public Optional<SubjectRef> find(UUID id) {
        return employees.findById(id).map(e -> refs(List.of(e)).get(e.getId()));
    }

    @Override
    public Map<UUID, SubjectRef> findAll(Collection<UUID> ids) {
        return refs(employees.findAllById(ids));
    }

    @Override
    public List<SearchHit> search(String pattern, int limit) {
        List<UUID> ids = queries.searchEmployees(pattern, limit);
        Map<UUID, SubjectRef> refs = refs(employees.findAllById(ids));
        return ids.stream().filter(refs::containsKey).map(id -> {
            SubjectRef r = refs.get(id);
            return new SearchHit(TYPE, id, r.label(), null, r.archived());
        }).toList();
    }

    private Map<UUID, SubjectRef> refs(List<Employee> list) {
        Map<UUID, PartyBrief> people = parties.briefs(list.stream().map(Employee::getPersonId).collect(Collectors.toSet()));
        LocalDate today = clock.today();
        return list.stream().collect(Collectors.toMap(Employee::getId, e -> new SubjectRef(TYPE, e.getId(),
                (people.containsKey(e.getPersonId()) ? people.get(e.getPersonId()).name() : "Employee") + " · "
                        + e.getEmployeeNumber(),
                EmploymentStatus.of(e.getStartDate(), e.getEndDate(), today) == EmploymentStatus.LEFT)));
    }
}
```


`hrms/EmployeeRelations.java`:

```java
package com.nexusops.hrms;

import com.nexusops.collaboration.SubjectKey;
import com.nexusops.collaboration.SubjectRelations;
import com.nexusops.hrms.domain.EmployeeRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** A person's timeline includes their employee record (D18). */
@Component
class EmployeeRelations implements SubjectRelations {

    private final EmployeeRepository employees;

    EmployeeRelations(EmployeeRepository employees) {
        this.employees = employees;
    }

    @Override
    public List<SubjectKey> related(String type, UUID id) {
        if (!"PARTY".equals(type)) {
            return List.of();
        }
        return employees.findByPersonId(id).map(e -> List.of(new SubjectKey(EmployeeSubjects.TYPE, e.getId())))
                .orElse(List.of());
    }
}
```

`hrms/HrmsEmploymentGuard.java`:

```java
package com.nexusops.hrms;

import com.nexusops.directory.EmploymentGuard;
import com.nexusops.hrms.domain.EmployeeRepository;
import com.nexusops.shared.web.ApiProblem;
import java.util.UUID;
import org.springframework.stereotype.Component;

/** D3: once HRMS keeps someone's employment, the directory can't edit their EMPLOYEE role by hand. */
@Component
class HrmsEmploymentGuard implements EmploymentGuard {

    static final String MANAGED = "This person's employment is managed in HRMS.";

    private final EmployeeRepository employees;

    HrmsEmploymentGuard(EmployeeRepository employees) {
        this.employees = employees;
    }

    @Override
    public void beforeEmployeeRoleChange(UUID personId) {
        if (employees.existsByPersonId(personId)) {
            throw ApiProblem.conflict(MANAGED);
        }
    }
}
```

In `DepartmentService`, inject `EmployeeRepository employees` and `HrClock clock`. In `archive`, before archiving a not-yet-archived department:

```java
            if (employees.anyCurrentIn(id, clock.today())) {
                throw ApiProblem.conflict(EMPLOYEES_LEFT);
            }
```

with `static final String EMPLOYEES_LEFT = "Move this department's employees first.";`.

- [ ] **Step 10: Web layer**

Add to `HrDtos`:

```java
    record EmployeeRequest(UUID personId, NewPerson person, String employeeNumber, UUID departmentId, UUID jobRoleId,
            UUID managerId, EmploymentType employmentType, LocalDate startDate, UUID userId, UUID onboardingTemplateId,
            Long version) {
        EmployeeCommand hire() {
            return new EmployeeCommand(personId, person, employeeNumber, departmentId, jobRoleId, managerId,
                    employmentType, startDate, userId, onboardingTemplateId);
        }

        EmployeeUpdate update() {
            return new EmployeeUpdate(employeeNumber, departmentId, jobRoleId, managerId, employmentType, startDate,
                    userId);
        }
    }

    record EndRequest(LocalDate endDate, String reason, Long version) {}

    record SkillsRequest(List<EmployeeSkillCommand> skills, Long version) {}
```

(imports `com.nexusops.hrms.EmployeeCommand`, `EmployeeSkillCommand`, `EmployeeUpdate`, `EmploymentType`, `NewPerson`, `java.time.LocalDate`.)

`hrms/web/EmployeeController.java`:

```java
package com.nexusops.hrms.web;

import com.nexusops.hrms.EmployeeQuery;
import com.nexusops.hrms.EmployeeService;
import com.nexusops.hrms.EmployeeSummary;
import com.nexusops.hrms.EmployeeView;
import com.nexusops.hrms.EmploymentStatus;
import com.nexusops.hrms.EndEmployment;
import com.nexusops.hrms.SkillGapView;
import com.nexusops.hrms.web.HrDtos.EmployeeRequest;
import com.nexusops.hrms.web.HrDtos.EndRequest;
import com.nexusops.hrms.web.HrDtos.SkillsRequest;
import com.nexusops.hrms.web.HrDtos.VersionRequest;
import com.nexusops.shared.web.PageResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/employees")
class EmployeeController {

    private final EmployeeService employees;

    EmployeeController(EmployeeService employees) {
        this.employees = employees;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('hr.employee.read')")
    PageResponse<EmployeeSummary> list(@RequestParam(required = false) String q,
            @RequestParam(required = false) EmploymentStatus status, @RequestParam(required = false) UUID departmentId,
            @RequestParam(required = false) UUID jobRoleId, @RequestParam(required = false) UUID managerId,
            @RequestParam(required = false) UUID personId, @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        return employees.list(new EmployeeQuery(q, status, departmentId, jobRoleId, managerId, personId), page, size);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('hr.employee.read')")
    EmployeeView get(@PathVariable UUID id) {
        return employees.get(id);
    }

    @GetMapping("/{id}/skill-gap")
    @PreAuthorize("hasAuthority('hr.employee.read')")
    SkillGapView skillGap(@PathVariable UUID id) {
        return employees.skillGap(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('hr.employee.update')")
    EmployeeView hire(@RequestBody EmployeeRequest request) {
        return employees.hire(request.hire());
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('hr.employee.update')")
    EmployeeView update(@PathVariable UUID id, @RequestBody EmployeeRequest request) {
        return employees.update(id, request.update(), request.version());
    }

    @PostMapping("/{id}/end")
    @PreAuthorize("hasAuthority('hr.employee.update')")
    EmployeeView end(@PathVariable UUID id, @RequestBody EndRequest request) {
        return employees.end(id, new EndEmployment(request.endDate(), request.reason()), request.version());
    }

    @PostMapping("/{id}/reinstate")
    @PreAuthorize("hasAuthority('hr.employee.update')")
    EmployeeView reinstate(@PathVariable UUID id, @RequestBody VersionRequest request) {
        return employees.reinstate(id, request.version());
    }

    @PutMapping("/{id}/skills")
    @PreAuthorize("hasAuthority('hr.employee.update')")
    EmployeeView skills(@PathVariable UUID id, @RequestBody SkillsRequest request) {
        return employees.setSkills(id, request.skills(), request.version());
    }
}
```

`hrms/web/HrMemberController.java`:

```java
package com.nexusops.hrms.web;

import com.nexusops.collaboration.AssigneeView;
import com.nexusops.hrms.EmployeeService;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/members")
class HrMemberController {

    private final EmployeeService employees;

    HrMemberController(EmployeeService employees) {
        this.employees = employees;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('hr.employee.update')")
    List<AssigneeView> members(@RequestParam(required = false) String q) {
        return employees.members(q);
    }
}
```

Add to `EmployeeApiIT.readersSeeEmployeesButOnlyUpdatersChangeThem`: `viewer.get("/api/v1/hr/members").andExpect(status().isForbidden());` and, as the owner, `owner.get("/api/v1/hr/members?q=" + ws.email().substring(0, 5)).andExpect(jsonPath("$[0].email").value(ws.email()));` (test owners are `owner@<slug>.test`).

- [ ] **Step 11: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*EmployeeApiIT' --tests '*EmploymentStatusTest' --tests '*ManagerChainTest' --tests '*SkillGapsTest' --tests '*DepartmentApiIT' --tests '*RlsCoverageIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest' --tests '*PartyRolesIT'`
Expected: PASS.

Notes for the implementer:
- `PartyRolesIT` (the existing directory role tests) must still pass. The guard only refuses when an employee record exists.
- The end-employment test expects the version sequence `0 → 1` (end) `→ 2` (reinstate). Each of `end` and `reinstate` changes the row once.
- In `managerLoopsAreRefused`, `b`'s version is still 0 when its manager is set, because it was never edited before.

- [ ] **Step 12: Full suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src
git commit -m "feat(hrms): employees as directory persons — hire, edit, end and reinstate, skills and skill gaps"
```

---

### Task 3: Onboarding templates and checklists that become real tasks

**Files:**
- Create: `backend/src/main/resources/db/migration/V30__onboarding.sql`
- Modify (collaboration): `backend/src/main/java/com/nexusops/collaboration/TaskService.java` (`statuses`)
- Create (hrms): `OnboardingOwner.java`, `OnboardingItemCommand.java`, `OnboardingTemplateCommand.java`, `OnboardingItemView.java`, `OnboardingTemplateView.java`, `OnboardingTemplateService.java`, `OnboardingService.java`, `HrSetup.java`; `hrms/domain/OnboardingTemplate.java`, `OnboardingTemplateRepository.java`, `OnboardingTemplateItem.java`, `OnboardingTemplateItemRepository.java`, `EmployeeOnboarding.java`, `EmployeeOnboardingRepository.java`; `hrms/web/OnboardingTemplateController.java`; Modify: `hrms/web/HrDtos.java`, `hrms/web/EmployeeController.java`
- Test: `backend/src/test/java/com/nexusops/hrms/OnboardingApiIT.java`; Modify: `RlsCoverageIT.java` (`onboarding_templates`, `onboarding_template_items`, `employee_onboardings`, `employee_onboarding_tasks`)

**Interfaces:**
- Consumes (Task 2):
  - `EmployeeService.find/byIds`;
  - the `EmployeeHireHook` and `OnboardingReader` extension points;
  - `OnboardingProgress(UUID templateId, String templateName, int done, int total)`;
  - `EmployeeCommand.onboardingTemplateId()`.
- Produces:
  - `TaskService.statuses(Collection<UUID> ids) → Map<UUID, TaskStatus>`.
  - `OnboardingOwner {HR, MANAGER, EMPLOYEE}`.
  - `OnboardingTemplateView(UUID id, String name, List<OnboardingItemView> items, Instant archivedAt, long version)`, with `OnboardingItemView(String title, String description, OnboardingOwner owner, int dueOffsetDays)`.
  - `OnboardingTemplateService`: public `list(boolean archived)`, `create`, `update`, `archive`, `restore`, `seedDefaults()`.
  - `OnboardingService`:
    - public `start(UUID employeeId, UUID templateId) → EmployeeView`;
    - implements `OnboardingReader` and `EmployeeHireHook`;
    - package-private `boolean inProgress(UUID employeeId)` (Task 7's dashboard).
  - `HrSetup`: the `WorkspaceRegistered` listener. Task 4 adds leave types to it.
  - Route `POST /api/v1/hr/employees/{id}/onboarding {templateId}` (`hr.employee.update`); routes `/api/v1/hr/onboarding-templates…` (GET `hr.employee.read`, writes `hr.settings.manage`).

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/nexusops/hrms/OnboardingApiIT.java`:

```java
package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class OnboardingApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hronb"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
    }

    private UUID standard() throws Exception {
        List<String> ids = Api.read(owner.get("/api/v1/hr/onboarding-templates"),
                "$[?(@.name == 'Standard onboarding')].id");
        return UUID.fromString(ids.getFirst());
    }

    private UUID userId(String email) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users where email = ?", UUID.class, email);
    }

    @Test
    void everyWorkspaceStartsWithAStandardTemplate() throws Exception {
        owner.get("/api/v1/hr/onboarding-templates")
                .andExpect(jsonPath("$[0].name").value("Standard onboarding"))
                .andExpect(jsonPath("$[0].items.length()").value(5))
                .andExpect(jsonPath("$[0].items[0].owner").value("HR"))
                .andExpect(jsonPath("$[0].items[4].dueOffsetDays").value(30));
    }

    @Test
    void hiringWithATemplateCreatesTasksForHrTheManagerAndTheNewHire() throws Exception {
        UUID managerRole = TestRoles.create(mvc, owner.session(), "Manager", "collaboration.task.read");
        Workspace managerLogin = members.create(ws.tenantId(), Set.of(managerRole));
        Workspace hireLogin = members.create(ws.tenantId(), Set.of(managerRole));
        UUID manager = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Ravi\",\"lastName\":"
                + "\"Kumar\"},\"employeeNumber\":\"M-1\",\"startDate\":\"2026-01-05\",\"userId\":\""
                + userId(managerLogin.email()) + "\"}"));
        UUID id = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Meera\",\"lastName\":"
                + "\"Iyer\"},\"employeeNumber\":\"E-1\",\"startDate\":\"2026-11-02\",\"managerId\":\"" + manager
                + "\",\"userId\":\"" + userId(hireLogin.email()) + "\",\"onboardingTemplateId\":\"" + standard() + "\"}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.onboarding.templateName").value("Standard onboarding"))
                .andExpect(jsonPath("$.onboarding.done").value(0))
                .andExpect(jsonPath("$.onboarding.total").value(5)));
        owner.get("/api/v1/tasks?subjectType=EMPLOYEE&subjectId=" + id + "&size=10")
                .andExpect(jsonPath("$.total").value(5))
                .andExpect(jsonPath("$.items[?(@.title == 'Prepare laptop and accounts')].assignee.id")
                        .value(Matchers.contains(userId(ws.email()).toString())))
                .andExpect(jsonPath("$.items[?(@.title == 'Prepare laptop and accounts')].dueOn")
                        .value(Matchers.contains("2026-11-02")))
                .andExpect(jsonPath("$.items[?(@.title == '30-day check-in')].assignee.id")
                        .value(Matchers.contains(userId(managerLogin.email()).toString())))
                .andExpect(jsonPath("$.items[?(@.title == '30-day check-in')].dueOn")
                        .value(Matchers.contains("2026-12-02")))
                .andExpect(jsonPath("$.items[?(@.title == 'Read the employee handbook')].assignee.id")
                        .value(Matchers.contains(userId(hireLogin.email()).toString())));
        assertThat(mail.sentTo(managerLogin.email())).isNotEmpty();
        assertThat(mail.sentTo(hireLogin.email())).isNotEmpty();
        // finishing a task moves the progress
        String taskId = Api.read(owner.get("/api/v1/tasks?subjectType=EMPLOYEE&subjectId=" + id + "&size=10"),
                "$.items[?(@.title == 'Prepare laptop and accounts')].id").toString().replaceAll("[\\[\\]\"]", "");
        owner.post("/api/v1/tasks/" + taskId + "/status", "{\"status\":\"DONE\"}").andExpect(status().isOk());
        owner.get("/api/v1/hr/employees/" + id).andExpect(jsonPath("$.onboarding.done").value(1));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action = 'OnboardingStarted'", Long.class)).isEqualTo(1);
    }

    @Test
    void withoutLinkedAccountsTheTasksGoToWhoeverStartedOnboarding() throws Exception {
        UUID id = TestHr.hire(owner, "Arun", "Shah", "E-2");
        owner.get("/api/v1/hr/employees/" + id).andExpect(jsonPath("$.onboarding").doesNotExist());
        owner.post("/api/v1/hr/employees/" + id + "/onboarding", "{\"templateId\":\"" + standard() + "\"}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.onboarding.total").value(5));
        owner.get("/api/v1/tasks?subjectType=EMPLOYEE&subjectId=" + id + "&size=10")
                .andExpect(jsonPath("$.items[*].assignee.id").value(Matchers.everyItem(
                        Matchers.is(userId(ws.email()).toString()))));
        owner.post("/api/v1/hr/employees/" + id + "/onboarding", "{\"templateId\":\"" + standard() + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Onboarding has already started for this employee."));
    }

    @Test
    void startingOnboardingChecksTheTemplate() throws Exception {
        UUID id = TestHr.hire(owner, "Arun", "Shah", "E-2");
        owner.post("/api/v1/hr/employees/" + id + "/onboarding", "{\"templateId\":\"" + UUID.randomUUID() + "\"}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("templateId"));
        owner.post("/api/v1/hr/employees/" + id + "/onboarding", "{}").andExpect(status().isBadRequest());
        owner.post("/api/v1/hr/onboarding-templates/" + standard() + "/archive", "").andExpect(status().isOk());
        owner.post("/api/v1/hr/employees/" + id + "/onboarding", "{\"templateId\":\"" + standard() + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("This record is archived."));
        owner.post("/api/v1/hr/employees/" + UUID.randomUUID() + "/onboarding", "{\"templateId\":\"" + standard() + "\"}")
                .andExpect(status().isNotFound());
    }

    @Test
    void templatesAreEditedAsAWhole() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/onboarding-templates", "{\"name\":\"Sales onboarding\",\"items\":["
                + "{\"title\":\"Shadow a sales call\",\"owner\":\"MANAGER\",\"dueOffsetDays\":5},"
                + "{\"title\":\"Set up CRM access\",\"description\":\"Ask IT\",\"owner\":\"HR\",\"dueOffsetDays\":0}]}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.items[*].title").value(Matchers.contains("Shadow a sales call",
                        "Set up CRM access"))));
        owner.put("/api/v1/hr/onboarding-templates/" + id, "{\"name\":\"Sales onboarding\",\"items\":["
                + "{\"title\":\"Set up CRM access\",\"owner\":\"HR\",\"dueOffsetDays\":1}],\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].dueOffsetDays").value(1));
        String[][] cases = {
                {"{\"name\":\"\",\"items\":[{\"title\":\"T\",\"owner\":\"HR\",\"dueOffsetDays\":0}]}", "name"},
                {"{\"name\":\"X\",\"items\":[]}", "items"},
                {"{\"name\":\"X\",\"items\":[{\"title\":\"\",\"owner\":\"HR\",\"dueOffsetDays\":0}]}", "items[0].title"},
                {"{\"name\":\"X\",\"items\":[{\"title\":\"T\",\"dueOffsetDays\":0}]}", "items[0].owner"},
                {"{\"name\":\"X\",\"items\":[{\"title\":\"T\",\"owner\":\"HR\",\"dueOffsetDays\":366}]}",
                        "items[0].dueOffsetDays"},
                {"{\"name\":\"X\",\"items\":[{\"title\":\"T\",\"owner\":\"HR\"}]}", "items[0].dueOffsetDays"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/hr/onboarding-templates", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/hr/onboarding-templates", "{\"name\":\"SALES ONBOARDING\",\"items\":[{\"title\":\"T\","
                + "\"owner\":\"HR\",\"dueOffsetDays\":0}]}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
    }

    @Test
    void onlySettingsManagersEditTemplates() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "HR viewer", "hr.employee.read");
        Api viewer = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        viewer.get("/api/v1/hr/onboarding-templates").andExpect(status().isOk());
        viewer.post("/api/v1/hr/onboarding-templates", "{}").andExpect(status().isForbidden());
    }
}
```

`Api.read(..., "$.items[?(…)].id")` returns a JSON array. The test strips it to the single id with `replaceAll`.

Add `"onboarding_templates", "onboarding_template_items", "employee_onboardings", "employee_onboarding_tasks"` to `RlsCoverageIT.EXPECTED_TENANT_TABLES`.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests '*OnboardingApiIT' --tests '*RlsCoverageIT'`
Expected: FAIL — 404s and the RLS coverage mismatch.

- [ ] **Step 3: Migration**

`backend/src/main/resources/db/migration/V30__onboarding.sql`:

```sql
-- HRMS onboarding (D10): checklist templates, and the tasks an employee's onboarding created.
CREATE TABLE onboarding_templates (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name         text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 80),
    name_key     text NOT NULL,
    archived_at  timestamptz,
    created_at   timestamptz NOT NULL,
    updated_at   timestamptz NOT NULL,
    version      bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name_key)
);
ALTER TABLE onboarding_templates ENABLE ROW LEVEL SECURITY;
ALTER TABLE onboarding_templates FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON onboarding_templates
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE onboarding_template_items (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    template_id      uuid NOT NULL,
    position         integer NOT NULL CHECK (position >= 0),
    title            text NOT NULL CHECK (length(btrim(title)) BETWEEN 1 AND 200),
    description      text CHECK (description IS NULL OR length(description) <= 2000),
    owner            text NOT NULL CHECK (owner IN ('HR', 'MANAGER', 'EMPLOYEE')),
    due_offset_days  integer NOT NULL CHECK (due_offset_days BETWEEN 0 AND 365),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, template_id, position),
    FOREIGN KEY (tenant_id, template_id) REFERENCES onboarding_templates (tenant_id, id) ON DELETE CASCADE
);
ALTER TABLE onboarding_template_items ENABLE ROW LEVEL SECURITY;
ALTER TABLE onboarding_template_items FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON onboarding_template_items
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE employee_onboardings (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    employee_id    uuid NOT NULL,
    template_id    uuid NOT NULL,
    template_name  text NOT NULL,
    started_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    started_at     timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, employee_id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id),
    FOREIGN KEY (tenant_id, template_id) REFERENCES onboarding_templates (tenant_id, id)
);
ALTER TABLE employee_onboardings ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_onboardings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_onboardings
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE employee_onboarding_tasks (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    onboarding_id  uuid NOT NULL,
    task_id        uuid NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, onboarding_id, task_id),
    FOREIGN KEY (tenant_id, onboarding_id) REFERENCES employee_onboardings (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (task_id) REFERENCES tasks (id)
);
ALTER TABLE employee_onboarding_tasks ENABLE ROW LEVEL SECURITY;
ALTER TABLE employee_onboarding_tasks FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON employee_onboarding_tasks
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Existing workspaces get the standard template (new ones get it from HrSetup).
DO $$
DECLARE
    t uuid;
    tpl uuid;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        tpl := gen_random_uuid();
        INSERT INTO onboarding_templates (id, tenant_id, name, name_key, created_at, updated_at)
        VALUES (tpl, t, 'Standard onboarding', 'standard onboarding', now(), now());
        INSERT INTO onboarding_template_items (id, tenant_id, template_id, position, title, owner, due_offset_days) VALUES
            (gen_random_uuid(), t, tpl, 0, 'Prepare laptop and accounts', 'HR', 0),
            (gen_random_uuid(), t, tpl, 1, 'Welcome meeting and team introductions', 'MANAGER', 0),
            (gen_random_uuid(), t, tpl, 2, 'Read the employee handbook', 'EMPLOYEE', 3),
            (gen_random_uuid(), t, tpl, 3, 'Complete required training', 'EMPLOYEE', 14),
            (gen_random_uuid(), t, tpl, 4, '30-day check-in', 'MANAGER', 30);
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
```

`tasks` (V12) has no `UNIQUE (tenant_id, id)`, so the task link is a plain FK to `tasks (id)`; RLS on both tables keeps it inside the tenant.

- [ ] **Step 4: Task statuses for progress**

In `collaboration/TaskService.java`, add:

```java
    /** Status of each task id that exists in this tenant (HRMS onboarding progress). No permission check: a module API. */
    @Transactional(readOnly = true)
    public Map<UUID, TaskStatus> statuses(Collection<UUID> ids) {
        TenantContext.requireTenantId();
        return tasks.findAllById(ids).stream().collect(Collectors.toMap(Task::getId, Task::getStatus));
    }
```

(imports `java.util.Collection` if missing.)

- [ ] **Step 5: Templates**

Records:

```java
package com.nexusops.hrms;

/** Who an onboarding item's task goes to (D10). */
public enum OnboardingOwner {
    HR, MANAGER, EMPLOYEE
}
```

```java
public record OnboardingItemCommand(String title, String description, OnboardingOwner owner, Integer dueOffsetDays) {}
```

```java
public record OnboardingTemplateCommand(String name, List<OnboardingItemCommand> items) {}
```

```java
public record OnboardingItemView(String title, String description, OnboardingOwner owner, int dueOffsetDays) {}
```

```java
public record OnboardingTemplateView(UUID id, String name, List<OnboardingItemView> items, Instant archivedAt,
        long version) {}
```

Domain:
- `OnboardingTemplate` mirrors `Skill` (table `onboarding_templates`; `rename` → `apply(name, key)`; plus `touch()` that sets `updatedAt`).
- `OnboardingTemplateRepository` has the four finders of `SkillRepository`, typed `OnboardingTemplate`.
- `OnboardingTemplateItem` mirrors `JobRoleSkill`:
  - columns `template_id`, `position`, `title`, `description`, `owner` (`@Enumerated(EnumType.STRING)`), `due_offset_days`, all `updatable = false`;
  - constructor in that order;
  - a getter for each.
- `OnboardingTemplateItemRepository`:
  - `findByTemplateIdOrderByPositionAsc(UUID)`;
  - `findByTemplateIdInOrderByPositionAsc(Collection<UUID>)`;
  - `@Modifying @Query("delete from OnboardingTemplateItem i where i.templateId = :templateId") void deleteByTemplate(UUID templateId)`.

`hrms/OnboardingTemplateService.java` mirrors `SkillService`. These are the rules `OnboardingApiIT` asserts:
- **Lock and messages:** `"hr-onboarding-templates"`; `NAME_TAKEN = "Another template already uses this name."` (409, field `name`); `UNKNOWN = "Choose an onboarding template in this workspace."`.
- **Validation:** name `Text.required(raw, 80, "name")` collapsed. Then the items:
  - `items` null or empty → 400 field `items` `"Add at least one item."`; more than 50 → 400 field `items` `"Use at most 50 items."`;
  - per item `i`: title `Text.required(…, 200, "items[i].title")` collapsed; description `Text.optional(…, 2000, "items[i].description")`;
  - owner null → 400 `items[i].owner` `"Choose who the task is for."`;
  - `dueOffsetDays` null or outside 0–365 → 400 `items[i].dueOffsetDays` `"Use 0 to 365 days after the start date."`.
- **`update` order:** find → `checkVersion` → validate → `requireNotArchived` → name uniqueness → `apply` → replace the items (delete + insert at positions 0…n−1) → `touch()` → flush → audit.
- **Audit:** `OnboardingTemplateCreated`/`Updated` (after: name, item count), `…Archived`, `…Restored`.
- **Public methods:** `list(boolean archived)` (by name), `create(OnboardingTemplateCommand)`, `update(UUID, OnboardingTemplateCommand, Long)`, `archive`, `restore`, `seedDefaults()`.
  - `seedDefaults()` takes the lock and, when there are no templates, creates "Standard onboarding" with exactly the five items of the migration (same titles, owners and offsets).
- **Package-private helpers:**
  - `OnboardingTemplate resolve(UUID, String field)` (unknown or null → 400 `UNKNOWN`);
  - `List<OnboardingTemplateItem> items(UUID templateId)`.
- **Views:** sort items by position.

- [ ] **Step 6: Starting onboarding**

Domain:

```java
package com.nexusops.hrms.domain;

import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One employee's onboarding (D10): which template it came from; its tasks are in employee_onboarding_tasks. */
@Entity
@Table(name = "employee_onboardings")
public class EmployeeOnboarding extends TenantOwnedEntity {

    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Column(name = "template_name", nullable = false, updatable = false)
    private String templateName;

    @Column(name = "started_by", updatable = false)
    private UUID startedBy;

    @Column(name = "started_at", nullable = false, updatable = false)
    private Instant startedAt;

    protected EmployeeOnboarding() {}

    public EmployeeOnboarding(UUID id, UUID employeeId, UUID templateId, String templateName, UUID startedBy) {
        super(id);
        this.employeeId = employeeId;
        this.templateId = templateId;
        this.templateName = templateName;
        this.startedBy = startedBy;
        this.startedAt = Instant.now();
    }

    public UUID getEmployeeId() { return employeeId; }
    public UUID getTemplateId() { return templateId; }
    public String getTemplateName() { return templateName; }
}
```

```java
package com.nexusops.hrms.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface EmployeeOnboardingRepository extends JpaRepository<EmployeeOnboarding, UUID> {

    Optional<EmployeeOnboarding> findByEmployeeId(UUID employeeId);

    boolean existsByEmployeeId(UUID employeeId);

    @Query(value = "select task_id from employee_onboarding_tasks where onboarding_id = :onboardingId",
            nativeQuery = true)
    List<UUID> taskIds(UUID onboardingId);

    @Modifying
    @Query(value = "insert into employee_onboarding_tasks (id, tenant_id, onboarding_id, task_id) "
            + "values (:id, :tenantId, :onboardingId, :taskId)", nativeQuery = true)
    void linkTask(UUID id, UUID tenantId, UUID onboardingId, UUID taskId);
}
```

(The link table has no entity; native statements carry `tenant_id` explicitly and RLS checks it.)

`hrms/OnboardingService.java`:

```java
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
```

`EmployeeService` injects `List<EmployeeHireHook>` and `List<OnboardingReader>`, and `OnboardingService` needs `EmployeeService`. The `@Lazy` on the `EmployeeService` parameter breaks that construction cycle. Spring injects a lazy proxy, and `OnboardingService` only calls it after startup. `TenantContext.userId()` returns `Optional<UUID>` of the signed-in user, the same accessor `TicketService` and `CategoryService` use.

`HrSetup.java`:

```java
package com.nexusops.hrms;

import com.nexusops.tenancy.WorkspaceRegistered;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Every new workspace starts with the HRMS defaults, in the signup transaction (D10; Task 4 adds leave types). */
@Component
class HrSetup {

    private final OnboardingTemplateService templates;

    HrSetup(OnboardingTemplateService templates) {
        this.templates = templates;
    }

    @EventListener
    void on(WorkspaceRegistered event) {
        templates.seedDefaults();
    }
}
```

- [ ] **Step 7: Web layer**

Add to `HrDtos`:

```java
    record OnboardingTemplateRequest(String name, List<OnboardingItemCommand> items, Long version) {
        OnboardingTemplateCommand command() {
            return new OnboardingTemplateCommand(name, items);
        }
    }

    record StartOnboardingRequest(UUID templateId) {}
```

`hrms/web/OnboardingTemplateController.java` at `/api/v1/hr/onboarding-templates` has the `SkillController` shape:
- `GET ?archived=` (`hr.employee.read`);
- `POST` (201), `PUT /{id}`, `POST /{id}/archive`, `POST /{id}/restore` (`hr.settings.manage`).

In `EmployeeController` (constructor gains `OnboardingService onboarding`):

```java
    @PostMapping("/{id}/onboarding")
    @PreAuthorize("hasAuthority('hr.employee.update')")
    EmployeeView startOnboarding(@PathVariable UUID id, @RequestBody StartOnboardingRequest request) {
        return onboarding.start(id, request.templateId());
    }
```

`start` resolves the template id (400) before finding the employee (404), as the global error order requires.

- [ ] **Step 8: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*OnboardingApiIT' --tests '*EmployeeApiIT' --tests '*RlsCoverageIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest' --tests '*Task*'`
Expected: PASS.

- [ ] **Step 9: Full suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src
git commit -m "feat(hrms): onboarding templates whose checklists become tasks for HR, the manager and the new hire"
```

---

### Task 4: Leave — types, holidays, requests counted in working days, balances, approvals and who's away

**Files:**
- Create: `backend/src/main/resources/db/migration/V31__leave.sql`
- Create (hrms): `WorkingDays.java`, `LeaveBalance.java`, `LeaveStatus.java`, `LeaveTypeRef.java`, `LeaveTypeCommand.java`, `LeaveTypeView.java`, `LeaveTypeService.java`, `HolidayView.java`, `HolidayService.java`, `LeaveRequestCommand.java`, `LeaveRequestView.java`, `LeaveQuery.java`, `LeaveBalanceView.java`, `LeaveAdjustmentCommand.java`, `AwayView.java`, `LeaveService.java`; `hrms/domain/LeaveType.java`, `LeaveTypeRepository.java`, `Holiday.java`, `HolidayRepository.java`, `LeaveRequest.java`, `LeaveRequestRepository.java`, `LeaveAdjustment.java`, `LeaveAdjustmentRepository.java`; `hrms/web/LeaveTypeController.java`, `HolidayController.java`, `LeaveController.java`
- Modify: `hrms/HrSetup.java` (seed leave types), `hrms/web/HrDtos.java`, `hrms/web/EmployeeController.java` (balances, adjustments)
- Test: `backend/src/test/java/com/nexusops/hrms/WorkingDaysTest.java`, `LeaveBalanceTest.java`, `LeaveSettingsApiIT.java`, `LeaveApiIT.java`; Modify: `support/TestHr.java` (`leaveType`), `RlsCoverageIT.java` (`leave_types`, `holidays`, `leave_requests`, `leave_adjustments`)

**Interfaces:**
- Consumes (Task 2): `EmployeeService.find/resolve/byIds/names/ref/forUser`, `EmploymentEndedEvent`, `HrClock`, `HrRecords`, `Members`.
- Produces:
  - **Pure rules:**
    - `WorkingDays.count(LocalDate start, LocalDate end, Set<LocalDate> holidays) → int`.
    - `LeaveBalance(BigDecimal entitlement, BigDecimal taken, BigDecimal pending, BigDecimal available)`, with `static LeaveBalance of(Integer allowance, BigDecimal adjustments, int taken, int pending)` (`entitlement` and `available` are null when `allowance` is null) and `static String days(BigDecimal)` (no trailing zeros).
  - **Records:**
    - `LeaveStatus {PENDING, APPROVED, REJECTED, CANCELLED}`
    - `LeaveTypeRef(UUID id, String name)`
    - `LeaveTypeView(UUID id, String name, boolean paid, Integer annualAllowance, Instant archivedAt, long version)`
    - `HolidayView(UUID id, LocalDate date, String name)`
    - `LeaveRequestCommand(UUID typeId, LocalDate startDate, LocalDate endDate, String reason)`
    - `LeaveRequestView(UUID id, EmployeeRef employee, LeaveTypeRef type, LocalDate startDate, LocalDate endDate, int days, String reason, LeaveStatus status, MemberRef requestedBy, Instant requestedAt, MemberRef decidedBy, Instant decidedAt, String decisionNote, Instant cancelledAt, long version)`
    - `LeaveQuery(LeaveStatus status, UUID employeeId, LocalDate from, LocalDate to)`
    - `LeaveBalanceView(LeaveTypeRef type, boolean paid, BigDecimal entitlement, BigDecimal taken, BigDecimal pending, BigDecimal available)`
    - `LeaveAdjustmentCommand(UUID typeId, Integer year, BigDecimal days, String reason)`
    - `AwayView(EmployeeRef employee, LeaveTypeRef type, LocalDate startDate, LocalDate endDate)`
  - **`LeaveService`:**
    - public:
      - `list(LeaveQuery, Integer page, Integer size)`;
      - `request(UUID employeeId, LeaveRequestCommand)` (on behalf, by an approver);
      - `approve(UUID, String note, Long version)`, `reject(UUID, String note, Long version)`, `cancel(UUID, Long version)` (an approver);
      - `away(LocalDate from, LocalDate to)`;
      - `balances(UUID employeeId, Integer year)`, `adjust(UUID employeeId, LeaveAdjustmentCommand)`;
    - package-private, for self-service (Task 6):
      - `LeaveRequestView requestOwn(Employee, LeaveRequestCommand)`;
      - `LeaveRequestView cancelOwn(Employee, UUID requestId, Long version)`;
      - `List<LeaveRequestView> forEmployee(UUID employeeId)`;
      - `List<LeaveBalanceView> balancesFor(Employee, int year)`;
    - package-private for the dashboard (Task 7): `long pendingCount()`.
  - `TestHr.leaveType(Api owner, String name) → UUID` (looks up a seeded type by name).

- [ ] **Step 1: Write the failing unit tests**

`backend/src/test/java/com/nexusops/hrms/WorkingDaysTest.java`:

```java
package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorkingDaysTest {

    static LocalDate d(String iso) {
        return LocalDate.parse(iso);
    }

    @Test
    void aWorkingWeekIsFiveDays() {
        assertThat(WorkingDays.count(d("2027-03-01"), d("2027-03-05"), Set.of())).isEqualTo(5); // Mon–Fri
    }

    @Test
    void fridayToMondayIsTwoDays() {
        assertThat(WorkingDays.count(d("2027-03-05"), d("2027-03-08"), Set.of())).isEqualTo(2);
    }

    @Test
    void aWeekendAloneIsNoWorkingDay() {
        assertThat(WorkingDays.count(d("2027-03-06"), d("2027-03-07"), Set.of())).isZero();
    }

    @Test
    void holidaysOnWeekdaysDontCountAndOnWeekendsChangeNothing() {
        assertThat(WorkingDays.count(d("2027-03-01"), d("2027-03-05"), Set.of(d("2027-03-03")))).isEqualTo(4);
        assertThat(WorkingDays.count(d("2027-03-01"), d("2027-03-07"), Set.of(d("2027-03-06")))).isEqualTo(5);
        assertThat(WorkingDays.count(d("2027-03-03"), d("2027-03-03"), Set.of(d("2027-03-03")))).isZero();
    }

    @Test
    void aSingleWorkingDayCountsOnce() {
        assertThat(WorkingDays.count(d("2027-03-03"), d("2027-03-03"), Set.of())).isEqualTo(1);
    }
}
```

`backend/src/test/java/com/nexusops/hrms/LeaveBalanceTest.java`:

```java
package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class LeaveBalanceTest {

    @Test
    void entitlementIsTheAllowancePlusAdjustmentsLessTakenAndPending() {
        LeaveBalance b = LeaveBalance.of(20, new BigDecimal("-2.5"), 5, 3);
        assertThat(b.entitlement()).isEqualByComparingTo("17.5");
        assertThat(b.taken()).isEqualByComparingTo("5");
        assertThat(b.pending()).isEqualByComparingTo("3");
        assertThat(b.available()).isEqualByComparingTo("9.5");
    }

    @Test
    void unlimitedTypesHaveNoEntitlementOrAvailable() {
        LeaveBalance b = LeaveBalance.of(null, BigDecimal.ZERO, 4, 1);
        assertThat(b.entitlement()).isNull();
        assertThat(b.available()).isNull();
        assertThat(b.taken()).isEqualByComparingTo("4");
    }

    @Test
    void daysPrintWithoutTrailingZeros() {
        assertThat(LeaveBalance.days(new BigDecimal("2.50"))).isEqualTo("2.5");
        assertThat(LeaveBalance.days(new BigDecimal("3.0"))).isEqualTo("3");
        assertThat(LeaveBalance.days(new BigDecimal("0.0"))).isEqualTo("0");
        assertThat(LeaveBalance.days(new BigDecimal("-1.0"))).isEqualTo("-1");
    }
}
```

Run: `cd backend && ./gradlew test --tests '*WorkingDaysTest' --tests '*LeaveBalanceTest'` — Expected: FAIL (types don't exist).

- [ ] **Step 2: The pure rules**

```java
package com.nexusops.hrms;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.Set;

/** D13: Monday to Friday in the range, inclusive, minus the workspace's public holidays. */
final class WorkingDays {

    private WorkingDays() {}

    static int count(LocalDate start, LocalDate end, Set<LocalDate> holidays) {
        int days = 0;
        for (LocalDate d = start; !d.isAfter(end); d = d.plusDays(1)) {
            DayOfWeek dow = d.getDayOfWeek();
            if (dow != DayOfWeek.SATURDAY && dow != DayOfWeek.SUNDAY && !holidays.contains(d)) {
                days++;
            }
        }
        return days;
    }
}
```

```java
package com.nexusops.hrms;

import java.math.BigDecimal;

/** D14 for one employee, type and year. Unlimited types (no allowance) have no entitlement and no available days. */
record LeaveBalance(BigDecimal entitlement, BigDecimal taken, BigDecimal pending, BigDecimal available) {

    static LeaveBalance of(Integer allowance, BigDecimal adjustments, int taken, int pending) {
        BigDecimal t = BigDecimal.valueOf(taken);
        BigDecimal p = BigDecimal.valueOf(pending);
        if (allowance == null) {
            return new LeaveBalance(null, t, p, null);
        }
        BigDecimal entitlement = BigDecimal.valueOf(allowance).add(adjustments);
        return new LeaveBalance(entitlement, t, p, entitlement.subtract(t).subtract(p));
    }

    /** "2.5", "3", "0": for messages. */
    static String days(BigDecimal value) {
        BigDecimal stripped = value.stripTrailingZeros();
        return stripped.signum() == 0 ? "0" : stripped.toPlainString();
    }
}
```

Run the two unit tests — Expected: PASS.

- [ ] **Step 3: Write the failing integration tests**

Add to `support/TestHr.java`:

```java
    public static UUID leaveType(Api owner, String name) throws Exception {
        java.util.List<String> ids = Api.read(owner.get("/api/v1/hr/leave-types"), "$[?(@.name == '" + name + "')].id");
        return UUID.fromString(ids.getFirst());
    }
```

`backend/src/test/java/com/nexusops/hrms/LeaveSettingsApiIT.java`:

```java
package com.nexusops.hrms;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class LeaveSettingsApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrlvset"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
    }

    @Test
    void everyWorkspaceStartsWithThreeLeaveTypes() throws Exception {
        owner.get("/api/v1/hr/leave-types")
                .andExpect(jsonPath("$[*].name").value(Matchers.contains("Annual leave", "Sick leave", "Unpaid leave")))
                .andExpect(jsonPath("$[0].annualAllowance").value(20))
                .andExpect(jsonPath("$[1].annualAllowance").value(10))
                .andExpect(jsonPath("$[2].annualAllowance").doesNotExist())
                .andExpect(jsonPath("$[2].paid").value(false));
    }

    @Test
    void leaveTypesAreCreatedEditedAndArchived() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/leave-types", "{\"name\":\"Study leave\",\"paid\":true,"
                + "\"annualAllowance\":5}").andExpect(status().isCreated()));
        owner.put("/api/v1/hr/leave-types/" + id, "{\"name\":\"Study leave\",\"paid\":false,\"annualAllowance\":null,"
                + "\"version\":0}").andExpect(status().isOk()).andExpect(jsonPath("$.annualAllowance").doesNotExist());
        owner.post("/api/v1/hr/leave-types", "{\"name\":\"study LEAVE\",\"paid\":true}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("name"));
        owner.post("/api/v1/hr/leave-types", "{\"name\":\"X\",\"paid\":true,\"annualAllowance\":367}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("annualAllowance"));
        owner.post("/api/v1/hr/leave-types", "{\"name\":\"X\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("paid"));
        owner.post("/api/v1/hr/leave-types/" + id + "/archive", "").andExpect(status().isOk());
        owner.get("/api/v1/hr/leave-types").andExpect(jsonPath("$[*].name", Matchers.not(Matchers.hasItem("Study leave"))));
    }

    @Test
    void holidaysAreAddedAndRemovedPerYear() throws Exception {
        UUID id = Api.id(owner.post("/api/v1/hr/holidays", "{\"date\":\"2027-01-26\",\"name\":\"Republic Day\"}")
                .andExpect(status().isCreated()));
        owner.post("/api/v1/hr/holidays", "{\"date\":\"2027-08-15\",\"name\":\"Independence Day\"}")
                .andExpect(status().isCreated());
        owner.post("/api/v1/hr/holidays", "{\"date\":\"2028-01-26\",\"name\":\"Republic Day\"}")
                .andExpect(status().isCreated());
        owner.post("/api/v1/hr/holidays", "{\"date\":\"2027-01-26\",\"name\":\"Again\"}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.errors[0].field").value("date"));
        owner.post("/api/v1/hr/holidays", "{\"name\":\"No date\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("date"));
        owner.get("/api/v1/hr/holidays?year=2027").andExpect(jsonPath("$[*].name").value(
                Matchers.contains("Republic Day", "Independence Day")));
        owner.delete("/api/v1/hr/holidays/" + id).andExpect(status().isNoContent());
        owner.get("/api/v1/hr/holidays?year=2027").andExpect(jsonPath("$.length()").value(1));
        owner.delete("/api/v1/hr/holidays/" + id).andExpect(status().isNotFound());
    }

    @Test
    void selfServiceUsersSeeLeaveTypesAndHolidaysButCantChangeThem() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Staff", "hr.self.service");
        Api staff = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        staff.get("/api/v1/hr/leave-types").andExpect(status().isOk());
        staff.get("/api/v1/hr/holidays?year=2027").andExpect(status().isOk());
        staff.post("/api/v1/hr/holidays", "{\"date\":\"2027-01-26\",\"name\":\"X\"}").andExpect(status().isForbidden());
        staff.post("/api/v1/hr/leave-types", "{\"name\":\"X\",\"paid\":true}").andExpect(status().isForbidden());
    }
}
```

`backend/src/test/java/com/nexusops/hrms/LeaveApiIT.java`:

```java
package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

/** Dates in 2027: 1 March is a Monday. */
@AutoConfigureMockMvc
class LeaveApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID annual;
    UUID unpaid;
    UUID meera;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrleave"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
        annual = TestHr.leaveType(owner, "Annual leave");
        unpaid = TestHr.leaveType(owner, "Unpaid leave");
        meera = TestHr.hire(owner, "Meera", "Iyer", "E-1");
    }

    private org.springframework.test.web.servlet.ResultActions ask(UUID employee, UUID type, String start, String end)
            throws Exception {
        return owner.post("/api/v1/hr/leave-requests", "{\"employeeId\":\"" + employee + "\",\"typeId\":\"" + type
                + "\",\"startDate\":\"" + start + "\",\"endDate\":\"" + end + "\"}");
    }

    private UUID userId(String email) {
        return OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users where email = ?", UUID.class, email);
    }

    @Test
    void aRequestCountsWorkingDaysAndIsApproved() throws Exception {
        owner.post("/api/v1/hr/holidays", "{\"date\":\"2027-03-03\",\"name\":\"Holi\"}").andExpect(status().isCreated());
        UUID id = Api.id(ask(meera, annual, "2027-03-01", "2027-03-05").andExpect(status().isCreated())
                .andExpect(jsonPath("$.days").value(4))
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.employee.name").value("Meera Iyer")));
        owner.get("/api/v1/hr/employees/" + meera + "/leave-balances?year=2027")
                .andExpect(jsonPath("$[0].type.name").value("Annual leave"))
                .andExpect(jsonPath("$[0].entitlement").value(20))
                .andExpect(jsonPath("$[0].pending").value(4))
                .andExpect(jsonPath("$[0].available").value(16));
        owner.post("/api/v1/hr/leave-requests/" + id + "/approve", "{\"note\":\"Enjoy\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.decisionNote").value("Enjoy"))
                .andExpect(jsonPath("$.decidedBy.id").value(userId(ws.email()).toString()));
        owner.get("/api/v1/hr/employees/" + meera + "/leave-balances?year=2027")
                .andExpect(jsonPath("$[0].taken").value(4)).andExpect(jsonPath("$[0].pending").value(0));
        owner.post("/api/v1/hr/leave-requests/" + id + "/approve", "{\"version\":1}").andExpect(status().isConflict());
        owner.get("/api/v1/hr/leave/away?from=2027-03-02&to=2027-03-02")
                .andExpect(jsonPath("$[*].employee.name").value(Matchers.contains("Meera Iyer")));
        owner.get("/api/v1/hr/leave/away?from=2027-03-08&to=2027-03-12").andExpect(jsonPath("$").isEmpty());
        owner.get("/api/v1/hr/leave-requests?status=APPROVED").andExpect(jsonPath("$.total").value(1));
        assertThat(OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select count(*) from audit_events where action in ('LeaveRequested', 'LeaveApproved')", Long.class))
                .isEqualTo(2);
    }

    @Test
    void fridayToMondayIsTwoDaysAndAWeekendOnlyRequestIsRefused() throws Exception {
        ask(meera, annual, "2027-03-05", "2027-03-08").andExpect(status().isCreated())
                .andExpect(jsonPath("$.days").value(2));
        ask(meera, annual, "2027-03-13", "2027-03-14").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Choose at least one working day."));
    }

    @Test
    void invalidRequestsAreFieldErrors() throws Exception {
        String[][] cases = {
                {"{\"employeeId\":\"" + UUID.randomUUID() + "\",\"typeId\":\"" + annual
                        + "\",\"startDate\":\"2027-03-01\",\"endDate\":\"2027-03-01\"}", "employeeId"},
                {"{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + UUID.randomUUID()
                        + "\",\"startDate\":\"2027-03-01\",\"endDate\":\"2027-03-01\"}", "typeId"},
                {"{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual + "\",\"endDate\":\"2027-03-01\"}", "startDate"},
                {"{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual
                        + "\",\"startDate\":\"2027-03-05\",\"endDate\":\"2027-03-01\"}", "endDate"},
                {"{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual
                        + "\",\"startDate\":\"2027-12-30\",\"endDate\":\"2028-01-03\"}", "endDate"},
                {"{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual
                        + "\",\"startDate\":\"2025-03-03\",\"endDate\":\"2025-03-03\"}", "startDate"},
                {"{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual + "\",\"startDate\":\"2027-03-01\","
                        + "\"endDate\":\"2027-03-01\",\"reason\":\"" + "x".repeat(501) + "\"}", "reason"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/hr/leave-requests", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.post("/api/v1/hr/leave-requests", "{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual
                + "\",\"startDate\":\"2027-12-30\",\"endDate\":\"2028-01-03\"}")
                .andExpect(jsonPath("$.errors[0].message").value("Split a request at the end of the year."));
    }

    @Test
    void overlappingRequestsAreRefusedButRejectedOrCancelledOnesDontCount() throws Exception {
        UUID first = Api.id(ask(meera, annual, "2027-03-01", "2027-03-05").andExpect(status().isCreated()));
        ask(meera, unpaid, "2027-03-05", "2027-03-08").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This overlaps another leave request."));
        owner.post("/api/v1/hr/leave-requests/" + first + "/reject", "{\"note\":\"Busy week\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REJECTED"));
        ask(meera, unpaid, "2027-03-05", "2027-03-08").andExpect(status().isCreated());
    }

    @Test
    void balanceCanBeUsedExactlyAndFractionsPrint() throws Exception {
        owner.post("/api/v1/hr/employees/" + meera + "/leave-adjustments", "{\"typeId\":\"" + annual
                + "\",\"year\":2027,\"days\":-17,\"reason\":\"Joined mid-year\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$[0].entitlement").value(3));
        ask(meera, annual, "2027-03-01", "2027-03-03").andExpect(status().isCreated()); // exactly 3
        ask(meera, annual, "2027-03-08", "2027-03-08").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Not enough leave left: 0 days available."));
        owner.post("/api/v1/hr/employees/" + meera + "/leave-adjustments", "{\"typeId\":\"" + annual
                + "\",\"year\":2027,\"days\":0.5,\"reason\":\"Goodwill\"}").andExpect(status().isOk());
        ask(meera, annual, "2027-03-08", "2027-03-08").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Not enough leave left: 0.5 days available."));
        // unlimited types are never short
        ask(meera, unpaid, "2027-03-08", "2027-03-12").andExpect(status().isCreated());
        String[][] bad = {
                {"{\"typeId\":\"" + annual + "\",\"year\":2027,\"days\":0,\"reason\":\"x\"}", "days"},
                {"{\"typeId\":\"" + annual + "\",\"year\":2027,\"days\":0.3,\"reason\":\"x\"}", "days"},
                {"{\"typeId\":\"" + annual + "\",\"year\":2027,\"days\":367,\"reason\":\"x\"}", "days"},
                {"{\"typeId\":\"" + annual + "\",\"year\":2027,\"days\":1}", "reason"},
                {"{\"typeId\":\"" + annual + "\",\"days\":1,\"reason\":\"x\"}", "year"},
        };
        for (String[] c : bad) {
            owner.post("/api/v1/hr/employees/" + meera + "/leave-adjustments", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
    }

    @Test
    void endingEmploymentCancelsOnlyLaterLeave() throws Exception {
        UUID march = Api.id(ask(meera, annual, "2027-03-01", "2027-03-05"));
        owner.post("/api/v1/hr/leave-requests/" + march + "/approve", "{\"version\":0}").andExpect(status().isOk());
        UUID june = Api.id(ask(meera, annual, "2027-06-07", "2027-06-11"));
        long version = ((Number) Api.read(owner.get("/api/v1/hr/employees/" + meera), "$.version")).longValue();
        owner.post("/api/v1/hr/employees/" + meera + "/end", "{\"endDate\":\"2027-04-30\",\"version\":" + version + "}")
                .andExpect(status().isOk());
        owner.get("/api/v1/hr/leave-requests?employeeId=" + meera)
                .andExpect(jsonPath("$.items[?(@.id == '" + march + "')].status").value(Matchers.contains("APPROVED")))
                .andExpect(jsonPath("$.items[?(@.id == '" + june + "')].status").value(Matchers.contains("CANCELLED")));
        owner.get("/api/v1/hr/employees/" + meera + "/leave-balances?year=2027")
                .andExpect(jsonPath("$[0].taken").value(5)).andExpect(jsonPath("$[0].pending").value(0));
        // and no new leave after the last day
        ask(meera, annual, "2027-05-03", "2027-05-03").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("endDate"));
    }

    @Test
    void theManagerHearsOfRequestsAndTheEmployeeOfDecisions() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Staff", "hr.self.service");
        Workspace managerLogin = members.create(ws.tenantId(), Set.of(role));
        Workspace staffLogin = members.create(ws.tenantId(), Set.of(role));
        UUID manager = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Ravi\",\"lastName\":"
                + "\"Kumar\"},\"employeeNumber\":\"M-1\",\"startDate\":\"2026-01-05\",\"userId\":\""
                + userId(managerLogin.email()) + "\"}"));
        UUID asha = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Asha\",\"lastName\":"
                + "\"Rao\"},\"employeeNumber\":\"E-2\",\"startDate\":\"2026-01-05\",\"managerId\":\"" + manager
                + "\",\"userId\":\"" + userId(staffLogin.email()) + "\"}"));
        UUID id = Api.id(ask(asha, annual, "2027-03-01", "2027-03-02"));
        assertThat(mail.sentTo(managerLogin.email())).anySatisfy(m -> assertThat(m.subject())
                .isEqualTo("Leave request: Asha Rao, 2027-03-01 to 2027-03-02"));
        owner.post("/api/v1/hr/leave-requests/" + id + "/approve", "{\"version\":0}").andExpect(status().isOk());
        assertThat(mail.sentTo(staffLogin.email())).anySatisfy(m -> assertThat(m.subject())
                .isEqualTo("Your leave request was approved"));
    }

    @Test
    void someoneElseMustDecideYourOwnRequest() throws Exception {
        UUID self = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Ada\",\"lastName\":\"Owner\"},"
                + "\"employeeNumber\":\"O-1\",\"startDate\":\"2026-01-05\",\"userId\":\"" + userId(ws.email()) + "\"}"));
        UUID id = Api.id(ask(self, annual, "2027-03-01", "2027-03-01"));
        owner.post("/api/v1/hr/leave-requests/" + id + "/approve", "{\"version\":0}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("Someone else must decide your own request."));
        owner.post("/api/v1/hr/leave-requests/" + id + "/reject", "{\"version\":0}").andExpect(status().isConflict());
        owner.post("/api/v1/hr/leave-requests/" + id + "/cancel", "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void approversCantCancelLeaveThatIsOver() throws Exception {
        UUID id = Api.id(ask(meera, annual, "2027-03-01", "2027-03-05"));
        owner.post("/api/v1/hr/leave-requests/" + id + "/approve", "{\"version\":0}").andExpect(status().isOk());
        OwnerJdbc.ownerAs(ws.tenantId()).update("update leave_requests set start_date = date '2026-03-02', "
                + "end_date = date '2026-03-06' where id = ?", id);
        owner.post("/api/v1/hr/leave-requests/" + id + "/cancel", "{\"version\":1}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This request can no longer be cancelled."));
    }

    @Test
    void onlyApproversSeeAndDecideRequests() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "HR viewer", "hr.employee.read");
        Api viewer = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        viewer.get("/api/v1/hr/leave-requests").andExpect(status().isForbidden());
        viewer.get("/api/v1/hr/leave/away?from=2027-03-01&to=2027-03-05").andExpect(status().isForbidden());
        viewer.get("/api/v1/hr/employees/" + meera + "/leave-balances?year=2027").andExpect(status().isOk());
        viewer.post("/api/v1/hr/employees/" + meera + "/leave-adjustments", "{}").andExpect(status().isForbidden());
    }
}
```

Add `"leave_types", "holidays", "leave_requests", "leave_adjustments"` to `RlsCoverageIT.EXPECTED_TENANT_TABLES`.

- [ ] **Step 4: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*LeaveSettingsApiIT' --tests '*LeaveApiIT' --tests '*RlsCoverageIT'`
Expected: FAIL — 404s and the RLS coverage mismatch.

- [ ] **Step 5: Migration**

`backend/src/main/resources/db/migration/V31__leave.sql`:

```sql
-- HRMS leave (D11–D14): types, public holidays, requests (working days stored), and append-only balance adjustments.
CREATE TABLE leave_types (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    name              text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 60),
    name_key          text NOT NULL,
    paid              boolean NOT NULL,
    annual_allowance  integer CHECK (annual_allowance IS NULL OR annual_allowance BETWEEN 0 AND 366),
    position          integer NOT NULL CHECK (position >= 0),
    archived_at       timestamptz,
    created_at        timestamptz NOT NULL,
    updated_at        timestamptz NOT NULL,
    version           bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, name_key)
);
ALTER TABLE leave_types ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_types FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leave_types
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE holidays (
    id          uuid PRIMARY KEY,
    tenant_id   uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    day         date NOT NULL,
    name        text NOT NULL CHECK (name = btrim(name) AND length(name) BETWEEN 1 AND 80),
    created_at  timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, day)
);
ALTER TABLE holidays ENABLE ROW LEVEL SECURITY;
ALTER TABLE holidays FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON holidays
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE leave_requests (
    id             uuid PRIMARY KEY,
    tenant_id      uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    employee_id    uuid NOT NULL,
    type_id        uuid NOT NULL,
    start_date     date NOT NULL,
    end_date       date NOT NULL,
    days           integer NOT NULL CHECK (days BETWEEN 1 AND 366),
    reason         text CHECK (reason IS NULL OR length(reason) <= 500),
    status         text NOT NULL CHECK (status IN ('PENDING', 'APPROVED', 'REJECTED', 'CANCELLED')),
    requested_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    requested_at   timestamptz NOT NULL,
    decided_by     uuid REFERENCES users (id) ON DELETE SET NULL,
    decided_at     timestamptz,
    decision_note  text CHECK (decision_note IS NULL OR length(decision_note) <= 500),
    cancelled_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    cancelled_at   timestamptz,
    updated_at     timestamptz NOT NULL,
    version        bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK (end_date >= start_date),
    CHECK (extract(year from start_date) = extract(year from end_date)),
    CHECK (status <> 'PENDING' OR decided_at IS NULL),
    CHECK (status NOT IN ('APPROVED', 'REJECTED') OR decided_at IS NOT NULL),
    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id),
    FOREIGN KEY (tenant_id, type_id) REFERENCES leave_types (tenant_id, id)
);
CREATE INDEX leave_requests_employee_idx ON leave_requests (tenant_id, employee_id, start_date);
CREATE INDEX leave_requests_status_idx ON leave_requests (tenant_id, status, start_date);
ALTER TABLE leave_requests ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_requests FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leave_requests
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE leave_adjustments (
    id           uuid PRIMARY KEY,
    tenant_id    uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    employee_id  uuid NOT NULL,
    type_id      uuid NOT NULL,
    year         integer NOT NULL CHECK (year BETWEEN 2000 AND 2100),
    days         numeric(5, 1) NOT NULL CHECK (days <> 0 AND days BETWEEN -366 AND 366 AND days * 2 = trunc(days * 2)),
    reason       text NOT NULL CHECK (length(btrim(reason)) BETWEEN 1 AND 200),
    created_by   uuid REFERENCES users (id) ON DELETE SET NULL,
    created_at   timestamptz NOT NULL,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id),
    FOREIGN KEY (tenant_id, type_id) REFERENCES leave_types (tenant_id, id)
);
CREATE INDEX leave_adjustments_employee_idx ON leave_adjustments (tenant_id, employee_id, year);
ALTER TABLE leave_adjustments ENABLE ROW LEVEL SECURITY;
ALTER TABLE leave_adjustments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leave_adjustments
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- append-only, like stock_movements and ticket_messages
REVOKE UPDATE, DELETE ON leave_adjustments FROM nexusops_app;

-- Existing workspaces get the default leave types (new ones get them from HrSetup).
DO $$
DECLARE
    t uuid;
BEGIN
    FOR t IN SELECT id FROM tenants LOOP
        PERFORM set_config('app.tenant_id', t::text, true);
        INSERT INTO leave_types (id, tenant_id, name, name_key, paid, annual_allowance, position, created_at, updated_at)
        VALUES (gen_random_uuid(), t, 'Annual leave', 'annual leave', true, 20, 0, now(), now()),
               (gen_random_uuid(), t, 'Sick leave', 'sick leave', true, 10, 1, now(), now()),
               (gen_random_uuid(), t, 'Unpaid leave', 'unpaid leave', false, NULL, 2, now(), now());
    END LOOP;
    PERFORM set_config('app.tenant_id', '', true);
END
$$;
```

The `REVOKE` is the same statement `V26__ticket_messages.sql` uses. `holidays.day` avoids the reserved word `date`.

- [ ] **Step 6: Leave types and holidays**

Records:

```java
public enum LeaveStatus { PENDING, APPROVED, REJECTED, CANCELLED }
```

```java
public record LeaveTypeRef(UUID id, String name) {}
```

```java
public record LeaveTypeCommand(String name, Boolean paid, Integer annualAllowance) {}
```

```java
public record LeaveTypeView(UUID id, String name, boolean paid, Integer annualAllowance, Instant archivedAt,
        long version) {}
```

```java
public record HolidayView(UUID id, LocalDate date, String name) {}
```

Domain:
- `LeaveType` mirrors `Skill`:
  - table `leave_types`;
  - extra columns `paid` (boolean), `annual_allowance` (Integer, nullable) and `position` (int, set once at creation);
  - `apply(name, key, paid, allowance)`;
  - getters `getName`, `isPaid`, `getAnnualAllowance`, `getPosition`, `getArchivedAt`, `getVersion`, `isArchived`.
- `LeaveTypeRepository`:
  - `findByArchivedAtIsNullOrderByPositionAscNameAsc()`;
  - `findByArchivedAtIsNotNullOrderByPositionAscNameAsc()`;
  - `existsByNameKey`, `existsByNameKeyAndIdNot`.
- `Holiday`:
  - `@Table(name = "holidays")`, with `@Column(name = "day") LocalDate date`, `name` and `created_at`, all `updatable = false`;
  - no version (holidays are added or removed, not edited).
- `HolidayRepository`:
  - `List<Holiday> findByDateBetweenOrderByDateAsc(LocalDate from, LocalDate to)`;
  - `boolean existsByDate(LocalDate date)`.

`LeaveTypeService` mirrors `SkillService`:
- **Lock:** `"hr-leave-types"`.
- **Messages:** `NAME_TAKEN = "Another leave type already uses this name."`; `UNKNOWN = "Choose a leave type in this workspace."`.
- **Validation:**
  - name: `Text.required(…, 60, "name")` collapsed;
  - `paid` null → 400 field `paid` `"Say whether this leave is paid."`;
  - `annualAllowance` outside 0–366 → 400 field `annualAllowance` `"Use 0 to 366 days, or leave it empty for no limit."`.
- **Creation:** `position = count()` at creation.
- **Audit:** `LeaveTypeCreated`/`Updated`/`Archived`/`Restored`.
- **Public methods:** `list(boolean archived)`, `create(LeaveTypeCommand)`, `update(UUID, LeaveTypeCommand, Long)`, `archive`, `restore`, `seedDefaults()`.
  - `seedDefaults()`: under the lock, when there are none, creates Annual leave (paid, 20), Sick leave (paid, 10) and Unpaid leave (unpaid, null) at positions 0, 1, 2.
- **Package-private helpers:** `LeaveType resolve(UUID, String field)`, `Map<UUID, LeaveType> byIds(Collection<UUID>)`, `List<LeaveType> active()`, `static LeaveTypeRef ref(LeaveType)`.

`HolidayService`:
- `list(Integer year)`: null → the current year from `HrClock`; returns all holidays of that calendar year, by date.
- `add(LocalDate date, String name)`:
  - date null → 400 field `date` `"Enter a date."`;
  - name `Text.required(…, 80, "name")` collapsed;
  - lock `"hr-holidays"`;
  - an existing date → 409 field `date` `"There is already a holiday on this date."`;
  - audit `HolidayAdded`.
- `remove(UUID id)`: 404 when missing; deletes the row; audit `HolidayRemoved` (before: date, name).
- Package-private `Set<LocalDate> between(LocalDate from, LocalDate to)`.

Add `leaveTypes.seedDefaults()` to `HrSetup.on(...)`; the constructor gains `LeaveTypeService`.

- [ ] **Step 7: Requests, balances, adjustments**

Records:

```java
public record LeaveRequestCommand(UUID typeId, LocalDate startDate, LocalDate endDate, String reason) {}
```

```java
public record LeaveRequestView(UUID id, EmployeeRef employee, LeaveTypeRef type, LocalDate startDate,
        LocalDate endDate, int days, String reason, LeaveStatus status, MemberRef requestedBy, Instant requestedAt,
        MemberRef decidedBy, Instant decidedAt, String decisionNote, Instant cancelledAt, long version) {}
```

```java
/** All filters optional; {@code from}/{@code to} keep requests overlapping that range. */
public record LeaveQuery(LeaveStatus status, UUID employeeId, LocalDate from, LocalDate to) {}
```

```java
public record LeaveBalanceView(LeaveTypeRef type, boolean paid, BigDecimal entitlement, BigDecimal taken,
        BigDecimal pending, BigDecimal available) {}
```

```java
public record LeaveAdjustmentCommand(UUID typeId, Integer year, BigDecimal days, String reason) {}
```

```java
public record AwayView(EmployeeRef employee, LeaveTypeRef type, LocalDate startDate, LocalDate endDate) {}
```

`hrms/domain/LeaveRequest.java`:

```java
package com.nexusops.hrms.domain;

import com.nexusops.hrms.LeaveStatus;
import com.nexusops.shared.db.TenantOwnedEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "leave_requests")
public class LeaveRequest extends TenantOwnedEntity {

    @Column(name = "employee_id", nullable = false, updatable = false)
    private UUID employeeId;

    @Column(name = "type_id", nullable = false, updatable = false)
    private UUID typeId;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(nullable = false)
    private int days;

    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LeaveStatus status;

    @Column(name = "requested_by", updatable = false)
    private UUID requestedBy;

    @Column(name = "requested_at", nullable = false, updatable = false)
    private Instant requestedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decision_note")
    private String decisionNote;

    @Column(name = "cancelled_by")
    private UUID cancelledBy;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected LeaveRequest() {}

    public LeaveRequest(UUID id, UUID employeeId, UUID typeId, LocalDate startDate, LocalDate endDate, int days,
            String reason, UUID requestedBy) {
        super(id);
        this.employeeId = employeeId;
        this.typeId = typeId;
        this.startDate = startDate;
        this.endDate = endDate;
        this.days = days;
        this.reason = reason;
        this.status = LeaveStatus.PENDING;
        this.requestedBy = requestedBy;
        this.requestedAt = Instant.now();
        this.updatedAt = requestedAt;
    }

    public void decide(LeaveStatus outcome, UUID by, String note) {
        this.status = outcome;
        this.decidedBy = by;
        this.decidedAt = Instant.now();
        this.decisionNote = note;
        this.updatedAt = decidedAt;
    }

    public void cancel(UUID by) {
        this.status = LeaveStatus.CANCELLED;
        this.cancelledBy = by;
        this.cancelledAt = Instant.now();
        this.updatedAt = cancelledAt;
    }

    public UUID getEmployeeId() { return employeeId; }
    public UUID getTypeId() { return typeId; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public int getDays() { return days; }
    public String getReason() { return reason; }
    public LeaveStatus getStatus() { return status; }
    public UUID getRequestedBy() { return requestedBy; }
    public Instant getRequestedAt() { return requestedAt; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecisionNote() { return decisionNote; }
    public Instant getCancelledAt() { return cancelledAt; }
    public long getVersion() { return version; }
}
```

A cancelled request may keep the decision it had (an approved request cancelled later), so the CHECKs only forbid a decision on a pending request and require one on approved/rejected requests.

`hrms/domain/LeaveRequestRepository.java`:

```java
package com.nexusops.hrms.domain;

import com.nexusops.hrms.LeaveStatus;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public interface LeaveRequestRepository extends JpaRepository<LeaveRequest, UUID>,
        JpaSpecificationExecutor<LeaveRequest> {

    @Query("select count(r) > 0 from LeaveRequest r where r.employeeId = :employeeId and r.status in :statuses "
            + "and r.startDate <= :end and r.endDate >= :start")
    boolean overlaps(UUID employeeId, Collection<LeaveStatus> statuses, LocalDate start, LocalDate end);

    @Query("select coalesce(sum(r.days), 0) from LeaveRequest r where r.employeeId = :employeeId "
            + "and r.typeId = :typeId and r.status = :status and r.startDate between :from and :to")
    long sumDays(UUID employeeId, UUID typeId, LeaveStatus status, LocalDate from, LocalDate to);

    List<LeaveRequest> findByEmployeeIdOrderByStartDateDesc(UUID employeeId);

    @Query("select r from LeaveRequest r where r.employeeId = :employeeId and r.status in :statuses "
            + "and r.startDate > :after")
    List<LeaveRequest> startingAfter(UUID employeeId, Collection<LeaveStatus> statuses, LocalDate after);

    @Query("select r from LeaveRequest r where r.status = com.nexusops.hrms.LeaveStatus.APPROVED "
            + "and r.startDate <= :to and r.endDate >= :from order by r.startDate, r.id")
    List<LeaveRequest> approvedBetween(LocalDate from, LocalDate to);

    long countByStatus(LeaveStatus status);
}
```

`LeaveAdjustment` is an append-only entity:
- table `leave_adjustments`;
- every column `updatable = false`: `employee_id`, `type_id`, `year`, `days` (`BigDecimal`), `reason`, `created_by`, `created_at`;
- constructor in that order;
- getters.

`LeaveAdjustmentRepository`:

```java
public interface LeaveAdjustmentRepository extends JpaRepository<LeaveAdjustment, UUID> {

    /** null when there are none. */
    @Query("select sum(a.days) from LeaveAdjustment a where a.employeeId = :employeeId "
            + "and a.typeId = :typeId and a.year = :year")
    BigDecimal sumDays(UUID employeeId, UUID typeId, int year);
}
```

`hrms/LeaveService.java`:

```java
package com.nexusops.hrms;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.hrms.domain.Employee;
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
        if (c.startDate().isBefore(employee.getStartDate())) {
            throw ApiProblem.badRequestField("startDate", BEFORE_EMPLOYMENT);
        }
        if (employee.getEndDate() != null && c.endDate().isAfter(employee.getEndDate())) {
            throw ApiProblem.badRequestField("endDate", AFTER_EMPLOYMENT);
        }
        int days = WorkingDays.count(c.startDate(), c.endDate(), holidays.between(c.startDate(), c.endDate()));
        if (days < 1) {
            throw ApiProblem.badRequestField("endDate", NO_WORKING_DAY);
        }
        HrRecords.requireNotArchived(type.isArchived());
        locks.lock("hr-leave-" + employee.getId());
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
```

The "Not enough leave left" message prints `available`, never below zero. `endingEmploymentCancelsOnlyLaterLeave` cancels the June request because its start (`2027-06-07`) is after the end date (`2027-04-30`). The approved March request stays.

- [ ] **Step 8: Web layer**

Add to `HrDtos`:

```java
    record LeaveTypeRequest(String name, Boolean paid, Integer annualAllowance, Long version) {
        LeaveTypeCommand command() {
            return new LeaveTypeCommand(name, paid, annualAllowance);
        }
    }

    record HolidayRequest(LocalDate date, String name) {}

    record LeaveRequestRequest(UUID employeeId, UUID typeId, LocalDate startDate, LocalDate endDate, String reason) {
        LeaveRequestCommand command() {
            return new LeaveRequestCommand(typeId, startDate, endDate, reason);
        }
    }

    record DecisionRequest(String note, Long version) {}
```

Controllers:
- **`LeaveTypeController`** (`/api/v1/hr/leave-types`) has the `SkillController` shape.
  - GET: `@PreAuthorize("hasAnyAuthority('hr.employee.read', 'hr.self.service')")`.
  - Writes: `hr.settings.manage`.
- **`HolidayController`** (`/api/v1/hr/holidays`):
  - `GET ?year=` with `hasAnyAuthority('hr.employee.read', 'hr.self.service')`;
  - `POST` (201) and `DELETE /{id}` (`@ResponseStatus(HttpStatus.NO_CONTENT)`) with `hr.settings.manage`.
- **`LeaveController`** (`/api/v1/hr`), every handler `hr.leave.approve`:
  - `GET /leave-requests?status&employeeId&from&to&page&size` → `list(new LeaveQuery(...), page, size)`;
  - `POST /leave-requests` (201) → `request(body.employeeId(), body.command())`;
  - `POST /leave-requests/{id}/approve` (`DecisionRequest`);
  - `POST /leave-requests/{id}/reject` (`DecisionRequest`);
  - `POST /leave-requests/{id}/cancel` (`VersionRequest`);
  - `GET /leave/away?from&to` (`LocalDate` params, ISO).

In `EmployeeController` (constructor gains `LeaveService leave`):

```java
    @GetMapping("/{id}/leave-balances")
    @PreAuthorize("hasAuthority('hr.employee.read')")
    List<LeaveBalanceView> balances(@PathVariable UUID id, @RequestParam(required = false) Integer year) {
        return leave.balances(id, year);
    }

    @PostMapping("/{id}/leave-adjustments")
    @PreAuthorize("hasAuthority('hr.employee.update')")
    List<LeaveBalanceView> adjust(@PathVariable UUID id, @RequestBody LeaveAdjustmentCommand body) {
        return leave.adjust(id, body);
    }
```

`LeaveAdjustmentCommand` deserializes straight from JSON (record with `typeId`, `year`, `days`, `reason`).

- [ ] **Step 9: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*WorkingDaysTest' --tests '*LeaveBalanceTest' --tests '*LeaveSettingsApiIT' --tests '*LeaveApiIT' --tests '*EmployeeApiIT' --tests '*RlsCoverageIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest'`
Expected: PASS.

- [ ] **Step 10: Full suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src
git commit -m "feat(hrms): leave in working days with balances, adjustments, approvals, emails and who's away"
```

---

### Task 5: Training — courses that teach skills, assignments, completion that raises skills

**Files:**
- Create: `backend/src/main/resources/db/migration/V32__training.sql`
- Create (hrms): `TaughtSkill.java`, `TaughtSkillView.java`, `CourseCommand.java`, `CourseView.java`, `CourseService.java`, `TrainingStatus.java`, `TrainingQuery.java`, `TrainingAssignmentView.java`, `TrainingService.java`; `hrms/domain/Course.java`, `CourseRepository.java`, `CourseSkill.java`, `CourseSkillRepository.java`, `TrainingAssignment.java`, `TrainingAssignmentRepository.java`; `hrms/web/CourseController.java`, `TrainingController.java`; Modify: `hrms/web/HrDtos.java`
- Test: `backend/src/test/java/com/nexusops/hrms/TrainingApiIT.java`; Modify: `RlsCoverageIT.java` (`courses`, `course_skills`, `training_assignments`)

**Interfaces:**
- Consumes (Task 2): `EmployeeService.find/resolve/byIds/names/ref/raiseSkills`, `CourseFinder`, `CourseRef`, `EmploymentEndedEvent`, `EmploymentStatus`, `HrClock`, `SkillService.byIds/UNKNOWN`.
- Produces:
  - **Records:**
    - `TaughtSkill(UUID skillId, Integer level)`
    - `TaughtSkillView(UUID skillId, String name, int level)`
    - `CourseCommand(String title, String description, BigDecimal durationHours, String link, List<TaughtSkill> skills)`
    - `CourseView(UUID id, String title, String description, BigDecimal durationHours, String link, List<TaughtSkillView> skills, Instant archivedAt, long version)`
    - `TrainingStatus {ASSIGNED, COMPLETED, CANCELLED}`
    - `TrainingQuery(UUID employeeId, TrainingStatus status, Boolean overdue)`
    - `TrainingAssignmentView(UUID id, CourseRef course, EmployeeRef employee, LocalDate dueOn, TrainingStatus status, boolean overdue, MemberRef assignedBy, Instant assignedAt, LocalDate completedOn, String completionNote, long version)`
  - **`CourseService`:**
    - public `list(boolean archived)`, `get(UUID)`, `create`, `update`, `archive`, `restore`;
    - implements `CourseFinder` (active courses teaching the skill at ≥ the level, by title).
  - **`TrainingService`:**
    - public `list(TrainingQuery, Integer page, Integer size)`, `assign(UUID courseId, UUID employeeId, LocalDate dueOn)`, `complete(UUID id, String note, Long version)`, `cancel(UUID id, Long version)`;
    - package-private for Task 6:
      - `TrainingAssignmentView completeOwn(Employee, UUID id, String note, Long version)`;
      - `List<TrainingAssignmentView> openFor(UUID employeeId)`;
    - package-private for Task 7: `long overdueCount()`.

- [ ] **Step 1: Write the failing test**

`backend/src/test/java/com/nexusops/hrms/TrainingApiIT.java`:

```java
package com.nexusops.hrms;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class TrainingApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    UUID negotiation;
    UUID executive;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrtrain"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
        negotiation = TestHr.skill(owner, "Negotiation");
        executive = TestHr.jobRole(owner, "Account executive", null, Map.of(negotiation, 4));
    }

    private UUID course(String title, UUID skill, int level) throws Exception {
        return Api.id(owner.post("/api/v1/hr/courses", "{\"title\":\"" + title + "\",\"durationHours\":3.5,"
                + "\"link\":\"https://learn.example/neg\",\"skills\":[{\"skillId\":\"" + skill + "\",\"level\":" + level
                + "}]}").andExpect(status().isCreated()));
    }

    private UUID employeeWithGap(String userEmail) throws Exception {
        String user = userEmail == null ? "" : ",\"userId\":\"" + OwnerJdbc.ownerAs(ws.tenantId()).queryForObject(
                "select id from users where email = ?", UUID.class, userEmail) + "\"";
        UUID id = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Meera\",\"lastName\":\"Iyer\"},"
                + "\"employeeNumber\":\"E-1\",\"jobRoleId\":\"" + executive + "\",\"startDate\":\"2026-01-05\"" + user + "}"));
        owner.put("/api/v1/hr/employees/" + id + "/skills", "{\"skills\":[{\"skillId\":\"" + negotiation
                + "\",\"level\":2}],\"version\":0}").andExpect(status().isOk());
        return id;
    }

    @Test
    void coursesAreCreatedAndValidated() throws Exception {
        UUID id = course("Negotiation basics", negotiation, 3);
        owner.get("/api/v1/hr/courses/" + id).andExpect(jsonPath("$.durationHours").value(3.5))
                .andExpect(jsonPath("$.skills[0].name").value("Negotiation"))
                .andExpect(jsonPath("$.skills[0].level").value(3));
        String[][] cases = {
                {"{\"title\":\"\"}", "title"},
                {"{\"title\":\"T\",\"durationHours\":0}", "durationHours"},
                {"{\"title\":\"T\",\"link\":\"ftp://x\"}", "link"},
                {"{\"title\":\"T\",\"skills\":[{\"skillId\":\"" + negotiation + "\",\"level\":9}]}", "skills[0].level"},
                {"{\"title\":\"T\",\"skills\":[{\"skillId\":\"" + UUID.randomUUID() + "\",\"level\":1}]}",
                        "skills[0].skillId"},
        };
        for (String[] c : cases) {
            owner.post("/api/v1/hr/courses", c[0]).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].field").value(c[1]));
        }
        owner.put("/api/v1/hr/courses/" + id, "{\"title\":\"Negotiation basics\",\"skills\":[],\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.skills").isEmpty())
                .andExpect(jsonPath("$.link").doesNotExist());
    }

    @Test
    void theSkillGapRecommendsCoursesThatTeachEnough() throws Exception {
        UUID meera = employeeWithGap(null);
        UUID enough = course("Advanced negotiation", negotiation, 4);
        course("Negotiation basics", negotiation, 3);
        UUID archived = course("Old negotiation course", negotiation, 5);
        owner.post("/api/v1/hr/courses/" + archived + "/archive", "").andExpect(status().isOk());
        owner.get("/api/v1/hr/employees/" + meera + "/skill-gap")
                .andExpect(jsonPath("$.gaps[0].recommendedCourses[*].id").value(Matchers.contains(enough.toString())));
    }

    @Test
    void completingATrainingRaisesTheSkillAndClosesTheGap() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Staff", "hr.self.service");
        Workspace staff = members.create(ws.tenantId(), Set.of(role));
        UUID meera = employeeWithGap(staff.email());
        UUID enough = course("Advanced negotiation", negotiation, 4);
        UUID id = Api.id(owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough + "\",\"employeeId\":\""
                + meera + "\",\"dueOn\":\"2099-01-31\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ASSIGNED"))
                .andExpect(jsonPath("$.overdue").value(false)));
        assertThat(mail.sentTo(staff.email())).anySatisfy(m -> assertThat(m.subject())
                .isEqualTo("Training assigned: Advanced negotiation"));
        owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough + "\",\"employeeId\":\"" + meera + "\"}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value("This course is already assigned to this employee."));
        owner.post("/api/v1/hr/training-assignments/" + id + "/complete", "{\"note\":\"Passed\",\"version\":0}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.completedOn").exists());
        owner.get("/api/v1/hr/employees/" + meera).andExpect(jsonPath("$.skills[0].level").value(4));
        owner.get("/api/v1/hr/employees/" + meera + "/skill-gap").andExpect(jsonPath("$.gaps").isEmpty());
        // once completed it can be assigned again
        owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough + "\",\"employeeId\":\"" + meera + "\"}")
                .andExpect(status().isCreated());
    }

    @Test
    void completionNeverLowersASkill() throws Exception {
        UUID meera = employeeWithGap(null);
        UUID basics = course("Negotiation basics", negotiation, 1);
        UUID id = Api.id(owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + basics
                + "\",\"employeeId\":\"" + meera + "\"}"));
        owner.post("/api/v1/hr/training-assignments/" + id + "/complete", "{\"version\":0}").andExpect(status().isOk());
        owner.get("/api/v1/hr/employees/" + meera).andExpect(jsonPath("$.skills[0].level").value(2));
    }

    @Test
    void overdueAssignmentsAreFlaggedAndListed() throws Exception {
        UUID meera = employeeWithGap(null);
        UUID enough = course("Advanced negotiation", negotiation, 4);
        UUID id = Api.id(owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough
                + "\",\"employeeId\":\"" + meera + "\",\"dueOn\":\"2099-01-31\"}"));
        OwnerJdbc.ownerAs(ws.tenantId()).update("update training_assignments set due_on = ? where id = ?",
                LocalDate.now().minusDays(3), id);
        owner.get("/api/v1/hr/training-assignments?overdue=true").andExpect(jsonPath("$.items[*].id").value(
                Matchers.contains(id.toString()))).andExpect(jsonPath("$.items[0].overdue").value(true));
        owner.get("/api/v1/hr/training-assignments?employeeId=" + meera + "&status=ASSIGNED")
                .andExpect(jsonPath("$.total").value(1));
    }

    @Test
    void assigningChecksCourseAndEmployee() throws Exception {
        UUID meera = employeeWithGap(null);
        UUID enough = course("Advanced negotiation", negotiation, 4);
        owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + UUID.randomUUID() + "\",\"employeeId\":\""
                + meera + "\"}").andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].field").value("courseId"));
        owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough + "\",\"employeeId\":\""
                + UUID.randomUUID() + "\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("employeeId"));
        owner.post("/api/v1/hr/courses/" + enough + "/archive", "").andExpect(status().isOk());
        owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough + "\",\"employeeId\":\"" + meera + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("This record is archived."));
    }

    @Test
    void endingEmploymentCancelsOpenTraining() throws Exception {
        UUID meera = employeeWithGap(null);
        UUID enough = course("Advanced negotiation", negotiation, 4);
        UUID id = Api.id(owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough
                + "\",\"employeeId\":\"" + meera + "\"}"));
        long version = ((Number) Api.read(owner.get("/api/v1/hr/employees/" + meera), "$.version")).longValue();
        owner.post("/api/v1/hr/employees/" + meera + "/end", "{\"endDate\":\"" + LocalDate.now().minusDays(1)
                + "\",\"version\":" + version + "}").andExpect(status().isOk());
        owner.get("/api/v1/hr/training-assignments?employeeId=" + meera).andExpect(jsonPath("$.items[*].status")
                .value(Matchers.contains("CANCELLED")));
        owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + enough + "\",\"employeeId\":\"" + meera + "\"}")
                .andExpect(status().isConflict()).andExpect(jsonPath("$.detail").value("This employee has left."));
        assertThat(id).isNotNull();
    }

    @Test
    void readersSeeTrainingButOnlyTrainingManagersChangeIt() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "HR viewer", "hr.employee.read");
        Api viewer = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        viewer.get("/api/v1/hr/courses").andExpect(status().isOk());
        viewer.get("/api/v1/hr/training-assignments").andExpect(status().isOk());
        viewer.post("/api/v1/hr/courses", "{\"title\":\"X\"}").andExpect(status().isForbidden());
        viewer.post("/api/v1/hr/training-assignments", "{}").andExpect(status().isForbidden());
    }
}
```

Add `"courses", "course_skills", "training_assignments"` to `RlsCoverageIT.EXPECTED_TENANT_TABLES`.

- [ ] **Step 2: Run it to verify it fails**

Run: `cd backend && ./gradlew test --tests '*TrainingApiIT' --tests '*RlsCoverageIT'`
Expected: FAIL — 404s and the RLS coverage mismatch.

- [ ] **Step 3: Migration**

`backend/src/main/resources/db/migration/V32__training.sql`:

```sql
-- HRMS training (D9): courses that teach skills, and assignments of courses to employees.
CREATE TABLE courses (
    id              uuid PRIMARY KEY,
    tenant_id       uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    title           text NOT NULL CHECK (title = btrim(title) AND length(title) BETWEEN 1 AND 200),
    description     text CHECK (description IS NULL OR length(description) <= 5000),
    duration_hours  numeric(5, 1) CHECK (duration_hours IS NULL OR (duration_hours > 0 AND duration_hours <= 999)),
    link            text CHECK (link IS NULL OR (length(link) <= 500 AND link ~ '^https?://')),
    archived_at     timestamptz,
    created_at      timestamptz NOT NULL,
    updated_at      timestamptz NOT NULL,
    version         bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id)
);
ALTER TABLE courses ENABLE ROW LEVEL SECURITY;
ALTER TABLE courses FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON courses
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE course_skills (
    id         uuid PRIMARY KEY,
    tenant_id  uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    course_id  uuid NOT NULL,
    skill_id   uuid NOT NULL,
    level      integer NOT NULL CHECK (level BETWEEN 1 AND 5),
    UNIQUE (tenant_id, id),
    UNIQUE (tenant_id, course_id, skill_id),
    FOREIGN KEY (tenant_id, course_id) REFERENCES courses (tenant_id, id) ON DELETE CASCADE,
    FOREIGN KEY (tenant_id, skill_id) REFERENCES skills (tenant_id, id)
);
CREATE INDEX course_skills_skill_idx ON course_skills (tenant_id, skill_id, level);
ALTER TABLE course_skills ENABLE ROW LEVEL SECURITY;
ALTER TABLE course_skills FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON course_skills
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

CREATE TABLE training_assignments (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    course_id        uuid NOT NULL,
    employee_id      uuid NOT NULL,
    due_on           date,
    status           text NOT NULL CHECK (status IN ('ASSIGNED', 'COMPLETED', 'CANCELLED')),
    assigned_by      uuid REFERENCES users (id) ON DELETE SET NULL,
    assigned_at      timestamptz NOT NULL,
    completed_on     date,
    completion_note  text CHECK (completion_note IS NULL OR length(completion_note) <= 500),
    cancelled_at     timestamptz,
    updated_at       timestamptz NOT NULL,
    version          bigint NOT NULL DEFAULT 0,
    UNIQUE (tenant_id, id),
    CHECK ((status = 'COMPLETED') = (completed_on IS NOT NULL)),
    CHECK ((status = 'CANCELLED') = (cancelled_at IS NOT NULL)),
    FOREIGN KEY (tenant_id, course_id) REFERENCES courses (tenant_id, id),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employees (tenant_id, id)
);
CREATE UNIQUE INDEX training_assignments_open_uq ON training_assignments (tenant_id, course_id, employee_id)
    WHERE status = 'ASSIGNED';
CREATE INDEX training_assignments_employee_idx ON training_assignments (tenant_id, employee_id, status);
ALTER TABLE training_assignments ENABLE ROW LEVEL SECURITY;
ALTER TABLE training_assignments FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON training_assignments
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```

- [ ] **Step 4: Courses**

Records:

```java
public record TaughtSkill(UUID skillId, Integer level) {}
```

```java
public record TaughtSkillView(UUID skillId, String name, int level) {}
```

```java
public record CourseCommand(String title, String description, BigDecimal durationHours, String link,
        List<TaughtSkill> skills) {}
```

```java
public record CourseView(UUID id, String title, String description, BigDecimal durationHours, String link,
        List<TaughtSkillView> skills, Instant archivedAt, long version) {}
```

Domain:
- `Course` mirrors `JobRole`: table `courses`; fields `title`, `description`, `durationHours` (`BigDecimal`, column `duration_hours`), `link`, `archivedAt`, `createdAt`, `updatedAt`, `version`; `apply(title, description, durationHours, link)`; `archive`/`restore`/`isArchived`; getters. There is no title key, because titles needn't be unique.
- `CourseRepository`: `findByArchivedAtIsNullOrderByTitleAsc()`, `findByArchivedAtIsNotNullOrderByTitleAsc()`.
- `CourseSkill` mirrors `JobRoleSkill` (table `course_skills`, `course_id`, `skill_id`, `level`).
- `CourseSkillRepository`:
  - `findByCourseId`, `findByCourseIdIn(Collection<UUID>)`;
  - `@Modifying deleteByCourse(UUID)`;
  - `@Query("select s.courseId from CourseSkill s where s.skillId = :skillId and s.level >= :level") List<UUID> coursesTeaching(UUID skillId, int level)`.

`CourseService` mirrors `JobRoleService`'s structure:
- **Validation (400 order):**
  - title: `Text.required(…, 200, "title")` collapsed;
  - description: `Text.optional(…, 5000, "description")`;
  - `durationHours`: non-null and (≤ 0 or > 999) → `"durationHours"` `"Use more than 0 and at most 999 hours."`;
  - `link`: `Text.optional(…, 500, "link")` must start with `http://` or `https://` (case-insensitive) → `"link"` `"Use a web address starting with http:// or https://."`;
  - skills: per index, null id → `skills[i].skillId` `SkillService.UNKNOWN`; level null or outside 1–5 → `skills[i].level` `"Choose a level from 1 to 5."`; repeat → `skills[i].skillId` `"Each skill can be listed once."`; unknown skill → `skills[i].skillId`.
- **Then 409s:** a newly added archived skill (unchanged ones are kept, as in `JobRoleService`).
- **`update` order:** find → `checkVersion` → validate → `requireNotArchived(course)` → apply → replace the skills → flush.
- **Audit:** `CourseCreated`, `CourseUpdated`, `CourseArchived`, `CourseRestored`.
- **Views:** skills sorted by name.
- **Public methods:** `list(boolean archived)` (by title), `get(UUID)` (404), `create`, `update(UUID, CourseCommand, Long)`, `archive`, `restore`.
- **Package-private:** `Course resolve(UUID, String field)` (400 `"Choose a course in this workspace."`), `Map<UUID, Course> byIds(Collection<UUID>)`, `Map<UUID, Integer> taught(UUID courseId)`.
- **`CourseFinder`:**

```java
    @Override
    public List<CourseRef> teaching(UUID skillId, int minimumLevel) {
        List<UUID> ids = courseSkills.coursesTeaching(skillId, minimumLevel);
        return courses.findAllById(ids).stream().filter(c -> !c.isArchived())
                .sorted(Comparator.comparing(Course::getTitle, String.CASE_INSENSITIVE_ORDER))
                .map(c -> new CourseRef(c.getId(), c.getTitle())).toList();
    }
```

`CourseService` must not depend on `EmployeeService` (`EmployeeService` depends on `List<CourseFinder>`).

- [ ] **Step 5: Assignments**

```java
public enum TrainingStatus { ASSIGNED, COMPLETED, CANCELLED }
```

```java
/** All optional; {@code overdue} true keeps open assignments whose due date has passed. */
public record TrainingQuery(UUID employeeId, TrainingStatus status, Boolean overdue) {}
```

```java
public record TrainingAssignmentView(UUID id, CourseRef course, EmployeeRef employee, LocalDate dueOn,
        TrainingStatus status, boolean overdue, MemberRef assignedBy, Instant assignedAt, LocalDate completedOn,
        String completionNote, long version) {}
```

`hrms/domain/TrainingAssignment.java`:
- Mirrors `LeaveRequest` in shape: columns `course_id` and `employee_id` (`updatable = false`), plus `due_on`, `status` (`@Enumerated`), `assigned_by`, `assigned_at`, `completed_on`, `completion_note`, `cancelled_at`, `updated_at`, `@Version`.
- Constructor `TrainingAssignment(UUID id, UUID courseId, UUID employeeId, LocalDate dueOn, UUID assignedBy)` sets status `ASSIGNED`.
- Methods:
  - `complete(LocalDate on, String note)` → `COMPLETED`;
  - `cancel()` → `CANCELLED` with `cancelledAt = now`.
- Getters for every column.

`TrainingAssignmentRepository extends JpaRepository<…>, JpaSpecificationExecutor<…>`, with:
- `boolean existsByCourseIdAndEmployeeIdAndStatus(UUID, UUID, TrainingStatus)`;
- `List<TrainingAssignment> findByEmployeeIdAndStatusOrderByDueOnAsc(UUID, TrainingStatus)`;
- `@Query("select count(a) from TrainingAssignment a where a.status = com.nexusops.hrms.TrainingStatus.ASSIGNED and a.dueOn < :today") long overdue(LocalDate today)`.

`hrms/TrainingService.java`:

```java
package com.nexusops.hrms;

import com.nexusops.audit.AuditEntry;
import com.nexusops.audit.AuditService;
import com.nexusops.collaboration.MemberRef;
import com.nexusops.hrms.domain.Course;
import com.nexusops.hrms.domain.Employee;
import com.nexusops.hrms.domain.TrainingAssignment;
import com.nexusops.hrms.domain.TrainingAssignmentRepository;
import com.nexusops.identity.Members;
import com.nexusops.notifications.MailRequested;
import com.nexusops.notifications.OutgoingMail;
import com.nexusops.shared.Ids;
import com.nexusops.shared.TenantContext;
import com.nexusops.shared.Text;
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
    private final AuditService audit;
    private final ApplicationEventPublisher events;
    private final String appBaseUrl;

    TrainingService(TrainingAssignmentRepository assignments, CourseService courses, EmployeeService employees,
            Members members, HrClock clock, AuditService audit, ApplicationEventPublisher events,
            @Value("${nexusops.app.base-url}") String appBaseUrl) {
        this.assignments = assignments;
        this.courses = courses;
        this.employees = employees;
        this.members = members;
        this.clock = clock;
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
        Course course = courses.resolve(courseId, "courseId");
        Employee employee = employees.resolve(employeeId, "employeeId");
        HrRecords.requireNotArchived(course.isArchived());
        if (EmploymentStatus.of(employee.getStartDate(), employee.getEndDate(), clock.today()) == EmploymentStatus.LEFT) {
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
        HrRecords.checkVersion(a.getVersion(), version);
        String note = Text.optional(rawNote, 500, "note");
        if (a.getStatus() != TrainingStatus.ASSIGNED) {
            throw ApiProblem.conflict(NOT_OPEN);
        }
        a.complete(clock.today(), note);
        assignments.flush();
        employees.raiseSkills(a.getEmployeeId(), courses.taught(a.getCourseId()));
        audit.record(AuditEntry.of("TrainingCompleted", "TrainingAssignment", a.getId())
                .withAfter(note == null ? Map.of() : Map.of("note", note)));
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
```

`raiseSkills` (Task 2) keeps the higher of the held and taught levels. `completionNeverLowersASkill` pins that: level 2 held, a course teaches level 1, and the result stays 2.

- [ ] **Step 6: Web layer**

Add to `HrDtos`:

```java
    record CourseRequest(String title, String description, BigDecimal durationHours, String link,
            List<TaughtSkill> skills, Long version) {
        CourseCommand command() {
            return new CourseCommand(title, description, durationHours, link, skills);
        }
    }

    record AssignTrainingRequest(UUID courseId, UUID employeeId, LocalDate dueOn) {}

    record CompleteRequest(String note, Long version) {}
```

- **`CourseController`** (`/api/v1/hr/courses`):
  - `GET ?archived=` and `GET /{id}` (`hr.employee.read`);
  - `POST` (201), `PUT /{id}`, `POST /{id}/archive|restore` (`hr.training.manage`).
- **`TrainingController`** (`/api/v1/hr/training-assignments`):
  - `GET ?employeeId&status&overdue&page&size` (`hr.employee.read`);
  - `POST` (201, `hr.training.manage`) → `assign(courseId, employeeId, dueOn)`;
  - `POST /{id}/complete` (`CompleteRequest`) and `POST /{id}/cancel` (`VersionRequest`), both `hr.training.manage`.

- [ ] **Step 7: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*TrainingApiIT' --tests '*EmployeeApiIT' --tests '*RlsCoverageIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest'`
Expected: PASS.

- [ ] **Step 8: Full suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src
git commit -m "feat(hrms): courses that teach skills, training assignments, and completion that raises skills"
```

---

### Task 6: The policy library and self-service (My HR)

**Files:**
- Create: `backend/src/main/resources/db/migration/V33__hr_policies.sql`
- Create (hrms): `PolicyStatus.java`, `PolicyCommand.java`, `PolicyView.java`, `PolicySummary.java`, `PolicyService.java`, `MeView.java`, `SelfService.java`; `hrms/domain/HrPolicy.java`, `HrPolicyRepository.java`; `hrms/web/PolicyController.java`, `SelfServiceController.java`; Modify: `hrms/HrQueries.java` (`policyPage`), `hrms/web/HrDtos.java`
- Test: `backend/src/test/java/com/nexusops/hrms/PolicyApiIT.java`, `SelfServiceApiIT.java`; Modify: `RlsCoverageIT.java` (`hr_policies`)

**Interfaces:**
- Consumes:
  - Task 2: `EmployeeService.forUser/get`.
  - Task 3: `OnboardingService.progress`.
  - Task 4: `LeaveService.requestOwn/cancelOwn/forEmployee/balancesFor`.
  - Task 5: `TrainingService.completeOwn/openFor`.
  - Also `HrClock`, `HrRecords`.
- Produces:
  - **Records:**
    - `PolicyStatus {DRAFT, PUBLISHED, ARCHIVED}`
    - `PolicyCommand(String title, String body)`
    - `PolicyView(UUID id, String title, String body, PolicyStatus status, MemberRef author, Instant publishedAt, Instant createdAt, Instant updatedAt, long version)`
    - `PolicySummary(UUID id, String title, String excerpt, PolicyStatus status, Instant publishedAt, Instant updatedAt)` (excerpt: the first 200 characters of the body on one line)
    - `MeView(EmployeeView employee, int year, List<LeaveBalanceView> balances, List<LeaveRequestView> requests, List<TrainingAssignmentView> trainings)`. Onboarding progress is inside `employee.onboarding`.
  - **`PolicyService`:** public `list(String q, PolicyStatus status, Integer page, Integer size)`, `get`, `create`, `update`, `publish`, `unpublish`, `archive`.
  - **`SelfService`:** public `me()`, `requestLeave(LeaveRequestCommand)`, `cancelLeave(UUID id, Long version)`, `completeTraining(UUID id, String note, Long version)`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/hrms/PolicyApiIT.java`:

```java
package com.nexusops.hrms;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Set;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class PolicyApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrpol"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
    }

    private UUID policy(String title, String body) throws Exception {
        return Api.id(owner.post("/api/v1/hr/policies", "{\"title\":\"" + title + "\",\"body\":\"" + body + "\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.status").value("DRAFT")));
    }

    @Test
    void policiesAreWrittenPublishedUnpublishedAndArchived() throws Exception {
        UUID id = policy("Leave policy", "Annual leave is 20 days a year.");
        owner.post("/api/v1/hr/policies/" + id + "/publish", "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PUBLISHED")).andExpect(jsonPath("$.publishedAt").exists());
        owner.put("/api/v1/hr/policies/" + id, "{\"title\":\"Leave policy\",\"body\":\"Annual leave is 22 days.\","
                + "\"version\":1}").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PUBLISHED"));
        owner.post("/api/v1/hr/policies/" + id + "/unpublish", "{\"version\":2}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DRAFT")).andExpect(jsonPath("$.publishedAt").doesNotExist());
        owner.post("/api/v1/hr/policies/" + id + "/archive", "{\"version\":3}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ARCHIVED"));
        owner.post("/api/v1/hr/policies/" + id + "/publish", "{\"version\":4}").andExpect(status().isConflict());
        owner.put("/api/v1/hr/policies/" + id, "{\"title\":\"X\",\"body\":\"Y\",\"version\":4}")
                .andExpect(status().isConflict());
        owner.post("/api/v1/hr/policies", "{\"title\":\"\",\"body\":\"x\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("title"));
        owner.post("/api/v1/hr/policies", "{\"title\":\"T\",\"body\":\"\"}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("body"));
    }

    @Test
    void searchFindsWordsInTitlesAndBodiesAndToleratesOddInput() throws Exception {
        UUID leave = policy("Leave policy", "Annual leave is 20 days a year.");
        UUID travel = policy("Travel policy", "Book economy class; claim expenses within 30 days.");
        owner.post("/api/v1/hr/policies/" + leave + "/publish", "{\"version\":0}").andExpect(status().isOk());
        owner.post("/api/v1/hr/policies/" + travel + "/publish", "{\"version\":0}").andExpect(status().isOk());
        owner.get("/api/v1/hr/policies?q=expenses").andExpect(jsonPath("$.items[*].title").value(
                Matchers.contains("Travel policy")));
        owner.get("/api/v1/hr/policies?q=leave").andExpect(jsonPath("$.items[0].title").value("Leave policy"));
        owner.get("/api/v1/hr/policies?q=" + java.net.URLEncoder.encode("\"leave' & | !:*", java.nio.charset.StandardCharsets.UTF_8))
                .andExpect(status().isOk());
    }

    @Test
    void staffReadPublishedPoliciesOnly() throws Exception {
        UUID draft = policy("Draft policy", "Not yet.");
        UUID published = policy("Code of conduct", "Be kind.");
        owner.post("/api/v1/hr/policies/" + published + "/publish", "{\"version\":0}").andExpect(status().isOk());
        UUID role = TestRoles.create(mvc, owner.session(), "Staff", "hr.self.service");
        Api staff = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        staff.get("/api/v1/hr/policies").andExpect(jsonPath("$.items[*].title").value(Matchers.contains("Code of conduct")));
        staff.get("/api/v1/hr/policies?status=DRAFT").andExpect(jsonPath("$.total").value(0));
        staff.get("/api/v1/hr/policies/" + published).andExpect(status().isOk());
        staff.get("/api/v1/hr/policies/" + draft).andExpect(status().isNotFound());
        staff.post("/api/v1/hr/policies", "{\"title\":\"X\",\"body\":\"Y\"}").andExpect(status().isForbidden());
        owner.get("/api/v1/hr/policies").andExpect(jsonPath("$.total").value(2));
    }
}
```

`backend/src/test/java/com/nexusops/hrms/SelfServiceApiIT.java`:

```java
package com.nexusops.hrms;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestMembers;
import com.nexusops.support.TestRoles;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class SelfServiceApiIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;
    @Autowired TestMembers members;

    Workspace ws;
    Api owner;
    Api staff;
    UUID meera;
    UUID other;
    UUID annual;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrme"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
        annual = TestHr.leaveType(owner, "Annual leave");
        UUID role = TestRoles.create(mvc, owner.session(), "Staff", "hr.self.service");
        Workspace login = members.create(ws.tenantId(), Set.of(role));
        UUID userId = OwnerJdbc.ownerAs(ws.tenantId()).queryForObject("select id from users where email = ?",
                UUID.class, login.email());
        meera = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Meera\",\"lastName\":\"Iyer\"},"
                + "\"employeeNumber\":\"E-1\",\"startDate\":\"2026-01-05\",\"userId\":\"" + userId + "\"}"));
        other = TestHr.hire(owner, "Arun", "Shah", "E-2");
        staff = Api.login(mvc, login);
    }

    @Test
    void anEmployeeSeesTheirOwnRecordBalancesAndRequestsLeave() throws Exception {
        staff.get("/api/v1/hr/me").andExpect(status().isOk())
                .andExpect(jsonPath("$.employee.id").value(meera.toString()))
                .andExpect(jsonPath("$.balances[0].type.name").value("Annual leave"));
        UUID id = Api.id(staff.post("/api/v1/hr/me/leave-requests", "{\"typeId\":\"" + annual
                + "\",\"startDate\":\"2027-03-01\",\"endDate\":\"2027-03-02\",\"reason\":\"Family\"}")
                .andExpect(status().isCreated()).andExpect(jsonPath("$.employee.id").value(meera.toString()))
                .andExpect(jsonPath("$.days").value(2)));
        staff.get("/api/v1/hr/me").andExpect(jsonPath("$.requests[0].id").value(id.toString()));
        staff.post("/api/v1/hr/me/leave-requests/" + id + "/cancel", "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CANCELLED"));
    }

    @Test
    void anEmployeeCompletesTheirOwnTraining() throws Exception {
        UUID skill = TestHr.skill(owner, "Excel");
        UUID course = Api.id(owner.post("/api/v1/hr/courses", "{\"title\":\"Excel basics\",\"skills\":[{\"skillId\":\""
                + skill + "\",\"level\":2}]}"));
        UUID assignment = Api.id(owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + course
                + "\",\"employeeId\":\"" + meera + "\"}"));
        staff.get("/api/v1/hr/me").andExpect(jsonPath("$.trainings[0].course.title").value("Excel basics"));
        staff.post("/api/v1/hr/me/training/" + assignment + "/complete", "{\"version\":0}").andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        owner.get("/api/v1/hr/employees/" + meera).andExpect(jsonPath("$.skills[0].level").value(2));
    }

    @Test
    void selfServiceNeverReachesSomeoneElse() throws Exception {
        UUID theirs = Api.id(owner.post("/api/v1/hr/leave-requests", "{\"employeeId\":\"" + other + "\",\"typeId\":\""
                + annual + "\",\"startDate\":\"2027-03-01\",\"endDate\":\"2027-03-01\"}"));
        staff.post("/api/v1/hr/me/leave-requests/" + theirs + "/cancel", "{\"version\":0}").andExpect(status().isNotFound());
        UUID skill = TestHr.skill(owner, "Excel");
        UUID course = Api.id(owner.post("/api/v1/hr/courses", "{\"title\":\"Excel basics\",\"skills\":[{\"skillId\":\""
                + skill + "\",\"level\":2}]}"));
        UUID assignment = Api.id(owner.post("/api/v1/hr/training-assignments", "{\"courseId\":\"" + course
                + "\",\"employeeId\":\"" + other + "\"}"));
        staff.post("/api/v1/hr/me/training/" + assignment + "/complete", "{\"version\":0}").andExpect(status().isNotFound());
        staff.get("/api/v1/hr/employees/" + other).andExpect(status().isForbidden());
        staff.get("/api/v1/hr/leave-requests").andExpect(status().isForbidden());
        // the leave body can't name another employee either: the route ignores any employeeId
        staff.post("/api/v1/hr/me/leave-requests", "{\"employeeId\":\"" + other + "\",\"typeId\":\"" + annual
                + "\",\"startDate\":\"2027-04-05\",\"endDate\":\"2027-04-05\"}").andExpect(status().isCreated())
                .andExpect(jsonPath("$.employee.id").value(meera.toString()));
    }

    @Test
    void anUnlinkedAccountIsToldSo() throws Exception {
        UUID role = TestRoles.create(mvc, owner.session(), "Visitor", "hr.self.service");
        Api visitor = Api.login(mvc, members.create(ws.tenantId(), Set.of(role)));
        visitor.get("/api/v1/hr/me").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.detail").value("Your account isn't linked to an employee record."));
        visitor.post("/api/v1/hr/me/leave-requests", "{\"typeId\":\"" + annual + "\",\"startDate\":\"2027-03-01\","
                + "\"endDate\":\"2027-03-01\"}").andExpect(status().isNotFound());
    }

    @Test
    void anEmployeeCantCancelLeaveThatHasStarted() throws Exception {
        UUID id = Api.id(staff.post("/api/v1/hr/me/leave-requests", "{\"typeId\":\"" + annual
                + "\",\"startDate\":\"2027-03-01\",\"endDate\":\"2027-03-02\"}"));
        owner.post("/api/v1/hr/leave-requests/" + id + "/approve", "{\"version\":0}").andExpect(status().isOk());
        OwnerJdbc.ownerAs(ws.tenantId()).update("update leave_requests set start_date = date '2026-03-02', "
                + "end_date = date '2026-03-03' where id = ?", id);
        staff.post("/api/v1/hr/me/leave-requests/" + id + "/cancel", "{\"version\":1}").andExpect(status().isConflict());
    }
}
```

Add `"hr_policies"` to `RlsCoverageIT.EXPECTED_TENANT_TABLES`.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*PolicyApiIT' --tests '*SelfServiceApiIT' --tests '*RlsCoverageIT'`
Expected: FAIL — 404s and the RLS coverage mismatch.

- [ ] **Step 3: Migration**

`backend/src/main/resources/db/migration/V33__hr_policies.sql`:

```sql
-- HR policy library (D16). Titles weigh more than bodies in search (the policy-assistant baseline).
CREATE TABLE hr_policies (
    id            uuid PRIMARY KEY,
    tenant_id     uuid NOT NULL REFERENCES tenants (id) ON DELETE CASCADE,
    title         text NOT NULL CHECK (title = btrim(title) AND length(title) BETWEEN 1 AND 200),
    body          text NOT NULL CHECK (length(body) BETWEEN 1 AND 50000),
    status        text NOT NULL CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    author_id     uuid REFERENCES users (id) ON DELETE SET NULL,
    published_at  timestamptz,
    created_at    timestamptz NOT NULL,
    updated_at    timestamptz NOT NULL,
    version       bigint NOT NULL DEFAULT 0,
    search        tsvector GENERATED ALWAYS AS (
        setweight(to_tsvector('simple', title), 'A') || setweight(to_tsvector('simple', body), 'B')) STORED,
    UNIQUE (tenant_id, id),
    CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
);
CREATE INDEX hr_policies_status_idx ON hr_policies (tenant_id, status, updated_at);
CREATE INDEX hr_policies_search_idx ON hr_policies USING gin (search);
ALTER TABLE hr_policies ENABLE ROW LEVEL SECURITY;
ALTER TABLE hr_policies FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON hr_policies
    USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
```

- [ ] **Step 4: Policies**

The policy library is the knowledge base without categories. Build it by mirroring the committed HelpDesk files:

| HelpDesk file | HRMS file | Differences |
|---|---|---|
| `helpdesk/ArticleStatus.java` | `hrms/PolicyStatus.java` | same constants |
| `helpdesk/ArticleCommand.java` | `hrms/PolicyCommand.java` | `(String title, String body)` — no category |
| `helpdesk/ArticleView.java` | `hrms/PolicyView.java` | no `category` |
| `helpdesk/ArticleSummary.java` | `hrms/PolicySummary.java` | no `category` |
| `helpdesk/domain/KbArticle.java` | `hrms/domain/HrPolicy.java` | table `hr_policies`; no `categoryId`; the `search` column is not mapped |
| `helpdesk/domain/KbArticleRepository.java` | `hrms/domain/HrPolicyRepository.java` | typed `HrPolicy` |
| `helpdesk/ArticleService.java` | `hrms/PolicyService.java` | see below |
| `helpdesk/web/ArticleController.java` | `hrms/web/PolicyController.java` | base `/api/v1/hr/policies`; permissions below |

`PolicyService` follows `ArticleService` method for method, with these changes:
- **Manage permission:** `canManage()` is `CurrentAuthorities.has(HrPermissions.POLICY_MANAGE)`.
  - `list(q, status, page, size)`: without manage, statuses are `[PUBLISHED]` when status is null or PUBLISHED, else empty. With manage, null → `[DRAFT, PUBLISHED]`, else `[status]`.
  - `get` and every read: a non-published policy without manage → 404.
- **Search:** comes from `HrQueries.policyPage(String q, List<PolicyStatus> statuses, int limit, long offset)` (below), not `HelpDeskSearch`.
- **Validation:** title `Text.required(…, 200, "title")` collapsed; body `Text.required(…, 50000, "body")`.
- **Ordering:** `update` validates the body before `requireNotArchived`, as `ArticleService.update` does since its Task 5 fix.
- **Audit:** `PolicyCreated`, `PolicyUpdated`, `PolicyPublished`, `PolicyUnpublished`, `PolicyArchived`.
- **Transitions:** exactly as articles: publish and unpublish refuse archived (409); archive from DRAFT or PUBLISHED; idempotent no-ops when the state already matches.

`HrQueries.policyPage`:

```java
    Page policyPage(String rawQ, List<PolicyStatus> statuses, int limit, long offset) {
        MapSqlParameterSource params = new MapSqlParameterSource("tenant", TenantContext.requireTenantId())
                .addValue("statuses", statuses.stream().map(Enum::name).toList());
        StringBuilder where = new StringBuilder("p.tenant_id = :tenant and p.status in (:statuses)");
        String q = Text.optional(rawQ, 200, "q");
        String order = "p.updated_at desc, p.id desc";
        if (q != null) {
            where.append(" and p.search @@ websearch_to_tsquery('simple', :q)");
            params.addValue("q", q);
            order = "ts_rank(p.search, websearch_to_tsquery('simple', :q)) desc, " + order;
        }
        Long total = jdbc.queryForObject("select count(*) from hr_policies p where " + where, params, Long.class);
        params.addValue("limit", limit).addValue("offset", offset);
        List<UUID> ids = jdbc.queryForList("select p.id from hr_policies p where " + where + " order by " + order
                + " limit :limit offset :offset", params, UUID.class);
        return new Page(ids, total == null ? 0 : total);
    }
```

`PolicyController`:
- `GET` (`?q&status&page&size`) and `GET /{id}`: `@PreAuthorize("hasAnyAuthority('hr.self.service', 'hr.policy.manage')")`;
- `POST` (201), `PUT /{id}`, `POST /{id}/publish|unpublish|archive` (body `VersionRequest`): `hr.policy.manage`.

Add `record PolicyRequest(String title, String body, Long version) { PolicyCommand command() { return new PolicyCommand(title, body); } }` to `HrDtos`.

- [ ] **Step 5: Self-service**

```java
package com.nexusops.hrms;

import java.util.List;

/** D17: what a signed-in employee sees about themselves. */
public record MeView(EmployeeView employee, int year, List<LeaveBalanceView> balances, List<LeaveRequestView> requests,
        List<TrainingAssignmentView> trainings) {}
```

`hrms/SelfService.java`:

```java
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
```

`hrms/web/SelfServiceController.java` (`/api/v1/hr/me`, every handler `hr.self.service`):

```java
package com.nexusops.hrms.web;

import com.nexusops.hrms.LeaveRequestCommand;
import com.nexusops.hrms.LeaveRequestView;
import com.nexusops.hrms.MeView;
import com.nexusops.hrms.SelfService;
import com.nexusops.hrms.TrainingAssignmentView;
import com.nexusops.hrms.web.HrDtos.CompleteRequest;
import com.nexusops.hrms.web.HrDtos.VersionRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/me")
class SelfServiceController {

    /** Any employeeId in the body is ignored: self-service only ever acts on the caller. */
    record OwnLeaveRequest(UUID typeId, LocalDate startDate, LocalDate endDate, String reason) {}

    private final SelfService self;

    SelfServiceController(SelfService self) {
        this.self = self;
    }

    @GetMapping
    @PreAuthorize("hasAuthority('hr.self.service')")
    MeView me() {
        return self.me();
    }

    @PostMapping("/leave-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasAuthority('hr.self.service')")
    LeaveRequestView requestLeave(@RequestBody OwnLeaveRequest body) {
        return self.requestLeave(new LeaveRequestCommand(body.typeId(), body.startDate(), body.endDate(), body.reason()));
    }

    @PostMapping("/leave-requests/{id}/cancel")
    @PreAuthorize("hasAuthority('hr.self.service')")
    LeaveRequestView cancelLeave(@PathVariable UUID id, @RequestBody VersionRequest body) {
        return self.cancelLeave(id, body.version());
    }

    @PostMapping("/training/{id}/complete")
    @PreAuthorize("hasAuthority('hr.self.service')")
    TrainingAssignmentView completeTraining(@PathVariable UUID id, @RequestBody CompleteRequest body) {
        return self.completeTraining(id, body.note(), body.version());
    }
}
```

Unknown JSON properties (`employeeId` in the self-service leave body) must not fail deserialization. Spring Boot's default `ObjectMapper` has `FAIL_ON_UNKNOWN_PROPERTIES` off, and `selfServiceNeverReachesSomeoneElse` relies on it.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `cd backend && ./gradlew test --tests '*PolicyApiIT' --tests '*SelfServiceApiIT' --tests '*LeaveApiIT' --tests '*TrainingApiIT' --tests '*RlsCoverageIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest'`
Expected: PASS.

- [ ] **Step 7: Full suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src
git commit -m "feat(hrms): a searchable policy library and self-service for the signed-in employee"
```

---

### Task 7: The workforce dashboard, search, isolation proofs, the module gate and ADR-0013

**Files:**
- Create: `backend/src/main/java/com/nexusops/hrms/HrDashboardView.java`, `hrms/web/HrDashboardController.java`; Modify: `hrms/HrQueries.java` (`dashboard`), `collaboration/SearchService.java` (`ORDER`)
- Create: `backend/src/test/java/com/nexusops/HrRlsIT.java`, `backend/src/test/java/com/nexusops/hrms/HrDashboardIT.java`, `HrModuleGateIT.java`
- Modify: `backend/src/test/java/com/nexusops/CrossTenantApiIT.java`, `OpenApiContractIT.java`, `collaboration/SearchApiIT.java`
- Create: `docs/decisions/0013-hrms-employment-and-leave.md`; Modify: `docs/api/openapi.json` (re-exported)

**Interfaces:**
- Consumes (Tasks 1–6):
  - `HrQueries` (status fragments `LEFT`/`UPCOMING`/`ACTIVE`);
  - `LeaveService.away/pendingCount`, `TrainingService.overdueCount`, `OnboardingService`, `HrClock`;
  - `SkillService`, `DepartmentService`;
  - every `/api/v1/hr/**` route.
- Produces:
  - `HrDashboardView(long activeHeadcount, List<DepartmentCount> byDepartment, long upcomingStarts, long joinersLast90Days, long leaversLast90Days, List<AwayView> awayToday, List<AwayView> awayNext7Days, long pendingLeaveRequests, long overdueTrainings, long onboardingInProgress, List<SkillGapCount> topSkillGaps)`, with:
    - `record DepartmentCount(DepartmentRef department, long employees)` (department null = no department);
    - `record SkillGapCount(SkillRef skill, long employees)`;
    - annotated `@Schema(name = "HrDashboardView")` (the CRM and HelpDesk dashboards also have records named `*DashboardView`).
  - `GET /api/v1/hr/dashboard` (`hr.employee.read`).
  - `SearchService.ORDER = [PARTY, LEAD, OPPORTUNITY, PRODUCT, PURCHASE_ORDER, SALES_ORDER, TICKET, KB_ARTICLE, EMPLOYEE]`.

- [ ] **Step 1: Write the failing tests**

`backend/src/test/java/com/nexusops/hrms/HrDashboardIT.java`:

```java
package com.nexusops.hrms;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.nexusops.support.Api;
import com.nexusops.support.IntegrationTestSupport;
import com.nexusops.support.OwnerJdbc;
import com.nexusops.support.RecordingMailSender;
import com.nexusops.support.TestHr;
import com.nexusops.support.TestTenants;
import com.nexusops.support.TestTenants.Workspace;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

@AutoConfigureMockMvc
class HrDashboardIT extends IntegrationTestSupport {

    @Autowired MockMvc mvc;
    @Autowired RecordingMailSender mail;

    Workspace ws;
    Api owner;

    @BeforeEach
    void workspace() throws Exception {
        ws = TestTenants.signupAndVerify(mvc, mail, TestTenants.uniqueSlug("hrdash"));
        owner = Api.login(mvc, ws);
        TestHr.enable(owner);
    }

    @Test
    void anEmptyWorkspaceHasZeros() throws Exception {
        owner.get("/api/v1/hr/dashboard").andExpect(status().isOk())
                .andExpect(jsonPath("$.activeHeadcount").value(0))
                .andExpect(jsonPath("$.byDepartment").isEmpty())
                .andExpect(jsonPath("$.awayToday").isEmpty())
                .andExpect(jsonPath("$.topSkillGaps").isEmpty());
    }

    @Test
    void summarisesTheWorkforce() throws Exception {
        LocalDate today = LocalDate.now();
        UUID sales = TestHr.department(owner, "Sales");
        UUID negotiation = TestHr.skill(owner, "Negotiation");
        UUID role = TestHr.jobRole(owner, "Account executive", sales, Map.of(negotiation, 3));
        UUID meera = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Meera\",\"lastName\":\"Iyer\"},"
                + "\"employeeNumber\":\"S-1\",\"departmentId\":\"" + sales + "\",\"jobRoleId\":\"" + role
                + "\",\"startDate\":\"" + today.minusDays(10) + "\"}"));
        UUID arun = Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Arun\",\"lastName\":\"Shah\"},"
                + "\"employeeNumber\":\"S-2\",\"departmentId\":\"" + sales + "\",\"jobRoleId\":\"" + role
                + "\",\"startDate\":\"2025-01-06\"}"));
        TestHr.hire(owner, "Dev", "Patel", "O-1"); // started 2026-01-05, no department
        Api.id(owner.post("/api/v1/hr/employees", "{\"person\":{\"firstName\":\"Zoe\",\"lastName\":\"New\"},"
                + "\"employeeNumber\":\"N-1\",\"startDate\":\"" + today.plusDays(14) + "\"}"));
        UUID gone = TestHr.hire(owner, "Old", "Timer", "X-1");
        owner.post("/api/v1/hr/employees/" + gone + "/end", "{\"endDate\":\"" + today.minusDays(5) + "\",\"version\":0}")
                .andExpect(status().isOk());
        // Arun is away today (approved leave written directly: today may be a weekend)
        UUID annual = TestHr.leaveType(owner, "Annual leave");
        OwnerJdbc.ownerAs(ws.tenantId()).update("insert into leave_requests (id, tenant_id, employee_id, type_id, "
                + "start_date, end_date, days, status, requested_at, decided_at, updated_at) values (?, ?, ?, ?, ?, ?, 1, "
                + "'APPROVED', now(), now(), now())", UUID.randomUUID(), ws.tenantId(), arun, annual, today, today);
        owner.post("/api/v1/hr/leave-requests", "{\"employeeId\":\"" + meera + "\",\"typeId\":\"" + annual
                + "\",\"startDate\":\"" + LocalDate.of(today.getYear() + 1, 3, 1) + "\",\"endDate\":\""
                + LocalDate.of(today.getYear() + 1, 3, 1) + "\"}").andExpect(status().isCreated());
        owner.get("/api/v1/hr/dashboard")
                .andExpect(jsonPath("$.activeHeadcount").value(3))
                .andExpect(jsonPath("$.byDepartment[0].department.name").value("Sales"))
                .andExpect(jsonPath("$.byDepartment[0].employees").value(2))
                .andExpect(jsonPath("$.byDepartment[1].department").doesNotExist())
                .andExpect(jsonPath("$.byDepartment[1].employees").value(1))
                .andExpect(jsonPath("$.upcomingStarts").value(1))
                .andExpect(jsonPath("$.joinersLast90Days").value(1))
                .andExpect(jsonPath("$.leaversLast90Days").value(1))
                .andExpect(jsonPath("$.awayToday[0].employee.name").value("Arun Shah"))
                .andExpect(jsonPath("$.pendingLeaveRequests").value(1))
                .andExpect(jsonPath("$.overdueTrainings").value(0))
                .andExpect(jsonPath("$.topSkillGaps[0].skill.name").value("Negotiation"))
                .andExpect(jsonPath("$.topSkillGaps[0].employees").value(2));
    }
}
```

The expected figures:
- **Active headcount:** Meera, Arun and Dev (3). Zoe is upcoming and "Old Timer" left 5 days ago.
- **By department:** Sales has 2 (Meera, Arun); no department has 1 (Dev).
- **Upcoming starts:** Zoe, within the next 30 days.
- **Joiners in the last 90 days:** Meera (started 10 days ago).
- **Leavers in the last 90 days:** Old Timer.
- **Skill gaps:** both Sales employees hold no Negotiation, so that skill has 2 employees with a gap.
- **Pending leave:** Meera's request next year.

`backend/src/test/java/com/nexusops/hrms/HrModuleGateIT.java` mirrors the committed `helpdesk/HelpDeskModuleGateIT.java`.

The structural check covers every handler whose path starts with `/api/v1/hr/`. It needs at least 45 routes, and each expression, spaces removed, must match:

```java
    private static final Pattern HR_AUTHORITY = Pattern.compile(
            "^(hasAuthority\\('hr\\.[a-z.]+'\\)|hasAnyAuthority\\('hr\\.[a-z.]+'(,'hr\\.[a-z.]+')*\\))$");
```

The runtime test runs after `TestHr.enable(owner)` and `PUT /api/v1/tenant/modules/HRMS {"enabled":false}`, with random UUIDs where ids are needed. Each of these routes must answer 403:
- `GET /api/v1/hr/employees`, `GET /api/v1/hr/employees/{id}`, `POST /api/v1/hr/employees` (`{}`), `POST /api/v1/hr/employees/{id}/end` (`{}`), `GET /api/v1/hr/employees/{id}/skill-gap`;
- `GET /api/v1/hr/departments`, `GET /api/v1/hr/job-roles`, `GET /api/v1/hr/skills`;
- `GET /api/v1/hr/leave-types`, `GET /api/v1/hr/holidays`, `GET /api/v1/hr/leave-requests`, `POST /api/v1/hr/leave-requests/{id}/approve` (`{}`), `GET /api/v1/hr/leave/away`;
- `GET /api/v1/hr/courses`, `GET /api/v1/hr/training-assignments`;
- `GET /api/v1/hr/policies`;
- `GET /api/v1/hr/me`, `POST /api/v1/hr/me/leave-requests` (`{}`);
- `GET /api/v1/hr/onboarding-templates`;
- `GET /api/v1/hr/dashboard`.

Re-enabling HRMS makes `GET /api/v1/hr/employees` answer 200 again.

`backend/src/test/java/com/nexusops/HrRlsIT.java` mirrors the committed `HelpDeskRlsIT.java` (class `@TestInstance(PER_CLASS)`, `support.PostgresAssertions.assertDeniedByPostgres`). Its pieces:

```java
    static final List<String> TABLES = List.of("departments", "skills", "job_roles", "job_role_skills", "employees",
            "employee_skills", "onboarding_templates", "onboarding_template_items", "employee_onboardings",
            "employee_onboarding_tasks", "leave_types", "holidays", "leave_requests", "leave_adjustments", "courses",
            "course_skills", "training_assignments", "hr_policies");

    final UUID tenantA = UUID.randomUUID();
    final UUID tenantB = UUID.randomUUID();
    final UUID person = UUID.randomUUID();
    final UUID department = UUID.randomUUID();
    final UUID skill = UUID.randomUUID();
    final UUID jobRole = UUID.randomUUID();
    final UUID employee = UUID.randomUUID();
    final UUID template = UUID.randomUUID();
    final UUID onboarding = UUID.randomUUID();
    final UUID task = UUID.randomUUID();
    final UUID leaveType = UUID.randomUUID();
    final UUID leaveRequest = UUID.randomUUID();
    final UUID adjustment = UUID.randomUUID();
    final UUID course = UUID.randomUUID();
    final UUID assignment = UUID.randomUUID();
    final UUID policy = UUID.randomUUID();
    final AtomicInteger counter = new AtomicInteger();

    @BeforeAll
    void seedTenantB() {
        Timestamp now = Timestamp.from(Instant.now());
        for (UUID t : new UUID[] {tenantA, tenantB}) {
            OwnerJdbc.jdbc().update("insert into tenants (id, slug, name, status, plan_code, created_at, updated_at) "
                    + "values (?, ?, 'T', 'ACTIVE', 'FREE', ?, ?)", t, "hr-" + t.toString().substring(0, 8), now, now);
        }
        JdbcTemplate b = OwnerJdbc.ownerAs(tenantB);
        b.update("insert into parties (id, tenant_id, kind, name, name_key, first_name, last_name, created_at, updated_at) "
                + "values (?, ?, 'PERSON', 'Beta Person', 'beta person', 'Beta', 'Person', ?, ?)", person, tenantB, now, now);
        b.update("insert into departments (id, tenant_id, name, name_key, created_at, updated_at) "
                + "values (?, ?, 'Beta dept', 'beta dept', ?, ?)", department, tenantB, now, now);
        b.update("insert into skills (id, tenant_id, name, name_key, created_at, updated_at) "
                + "values (?, ?, 'Beta skill', 'beta skill', ?, ?)", skill, tenantB, now, now);
        b.update("insert into job_roles (id, tenant_id, title, title_key, department_id, created_at, updated_at) "
                + "values (?, ?, 'Beta role', 'beta role', ?, ?, ?)", jobRole, tenantB, department, now, now);
        b.update("insert into job_role_skills (id, tenant_id, job_role_id, skill_id, minimum_level) values (?, ?, ?, ?, 3)",
                UUID.randomUUID(), tenantB, jobRole, skill);
        b.update("insert into employees (id, tenant_id, person_id, employee_number, number_key, department_id, "
                + "job_role_id, employment_type, start_date, created_at, updated_at) values (?, ?, ?, 'B-1', 'b-1', ?, ?, "
                + "'FULL_TIME', date '2026-01-05', ?, ?)", employee, tenantB, person, department, jobRole, now, now);
        b.update("insert into employee_skills (id, tenant_id, employee_id, skill_id, level) values (?, ?, ?, ?, 2)",
                UUID.randomUUID(), tenantB, employee, skill);
        b.update("insert into onboarding_templates (id, tenant_id, name, name_key, created_at, updated_at) "
                + "values (?, ?, 'Beta onboarding', 'beta onboarding', ?, ?)", template, tenantB, now, now);
        b.update("insert into onboarding_template_items (id, tenant_id, template_id, position, title, owner, "
                + "due_offset_days) values (?, ?, ?, 0, 'Beta item', 'HR', 0)", UUID.randomUUID(), tenantB, template);
        b.update("insert into employee_onboardings (id, tenant_id, employee_id, template_id, template_name, started_at) "
                + "values (?, ?, ?, ?, 'Beta onboarding', ?)", onboarding, tenantB, employee, template, now);
        b.update("insert into tasks (id, tenant_id, title, status, priority, created_at, updated_at) "
                + "values (?, ?, 'Beta task', 'OPEN', 'NORMAL', ?, ?)", task, tenantB, now, now);
        b.update("insert into employee_onboarding_tasks (id, tenant_id, onboarding_id, task_id) values (?, ?, ?, ?)",
                UUID.randomUUID(), tenantB, onboarding, task);
        b.update("insert into leave_types (id, tenant_id, name, name_key, paid, annual_allowance, position, created_at, "
                + "updated_at) values (?, ?, 'Beta leave', 'beta leave', true, 20, 0, ?, ?)", leaveType, tenantB, now, now);
        b.update("insert into holidays (id, tenant_id, day, name, created_at) values (?, ?, date '2027-01-26', 'Beta day', ?)",
                UUID.randomUUID(), tenantB, now);
        b.update("insert into leave_requests (id, tenant_id, employee_id, type_id, start_date, end_date, days, status, "
                + "requested_at, updated_at) values (?, ?, ?, ?, date '2027-03-01', date '2027-03-01', 1, 'PENDING', ?, ?)",
                leaveRequest, tenantB, employee, leaveType, now, now);
        b.update("insert into leave_adjustments (id, tenant_id, employee_id, type_id, year, days, reason, created_at) "
                + "values (?, ?, ?, ?, 2027, 1.5, 'Beta', ?)", adjustment, tenantB, employee, leaveType, now);
        b.update("insert into courses (id, tenant_id, title, created_at, updated_at) values (?, ?, 'Beta course', ?, ?)",
                course, tenantB, now, now);
        b.update("insert into course_skills (id, tenant_id, course_id, skill_id, level) values (?, ?, ?, ?, 3)",
                UUID.randomUUID(), tenantB, course, skill);
        b.update("insert into training_assignments (id, tenant_id, course_id, employee_id, status, assigned_at, "
                + "updated_at) values (?, ?, ?, ?, 'ASSIGNED', ?, ?)", assignment, tenantB, course, employee, now, now);
        b.update("insert into hr_policies (id, tenant_id, title, body, status, created_at, updated_at) "
                + "values (?, ?, 'Beta policy', 'Body', 'DRAFT', ?, ?)", policy, tenantB, now, now);
    }
```

Tests, with the same shape as the HelpDesk ones:
1. **`anotherTenantsContextAndNoContextSeeNothing`:** for every table in `TABLES`, `select count(*)` is 0 as tenant A and with no context, and positive as tenant B.
2. **`insertsIntoAnotherTenantAreRejectedByRowLevelSecurity`**, for `departments`, `skills`, `employees`, `leave_types`, `holidays`, `leave_requests`, `leave_adjustments`, `courses`, `training_assignments` and `hr_policies`:
   - An `insertTenantBRow(JdbcTemplate as, String table)` helper writes a row valid for tenant B, with fresh keys and numbers from `counter`:
     - **`employees`** needs a new person first, since `person_id` is unique per tenant. Inside the helper, insert a party with the same template, then the employee referencing it.
     - **`holidays`** uses `date '2030-01-01' + counter`.
     - **`leave_requests`** uses distinct dates in 2028 for each call.
   - Inserting as tenant A and with no context is denied (42501).
   - The same insert as tenant B succeeds (positive control).
3. **`updatesAndDeletesOfAnotherTenantsRowsAffectNothing`**, for `departments`, `employees`, `leave_requests`, `courses` and `hr_policies`:
   - A harmless update as tenant B (set a column to its current value) affects 1 row (positive control).
   - The same update and a delete as tenant A affect 0.
   - The owner reads the row back unchanged.
4. **`leaveAdjustmentsAreAppendOnlyEvenForTheirOwnTenant`:** `update leave_adjustments set reason = 'x'` and `delete from leave_adjustments` as tenant B are both denied (42501). The owner still reads reason `'Beta'`.

Extend `CrossTenantApiIT` as Phase 7 did for HelpDesk. In `twoTenants()`, after B's plan is set to ENTERPRISE, B does the following:
- enables `HRMS`;
- creates a department, a skill, a job role (needing the skill) and an employee (a new person, job role and department set);
- creates a course teaching the skill;
- assigns that course to the employee (`assignmentB`);
- requests annual leave for the employee in 2027 (`leaveRequestB`);
- writes a draft policy (`policyB`);
- reads its seeded onboarding template and annual leave type.

Keep the ids in `departmentB, skillB, jobRoleB, employeeB, personB, courseB, assignmentB, leaveRequestB, policyB, templateB, leaveTypeB`, and add:

```java
    @Test
    void hrRecordsOfAnotherTenantAreInvisibleAndUntouchable() throws Exception {
        OwnerJdbc.jdbc().update("update tenants set plan_code = 'ENTERPRISE' where id = ?", a.tenantId());
        as(ownerA, put("/api/v1/tenant/modules/HRMS").contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":true}")).andExpect(status().isOk());
        // path ids → 404
        as(ownerA, get("/api/v1/hr/employees/" + employeeB)).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/hr/employees/" + employeeB + "/skill-gap")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/hr/employees/" + employeeB + "/leave-balances")).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/hr/employees/" + employeeB), "{\"employeeNumber\":\"X\",\"startDate\":\"2026-01-05\","
                + "\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/hr/employees/" + employeeB + "/end"), "{\"endDate\":\"2026-12-31\",\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/hr/employees/" + employeeB + "/reinstate"), "{\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/hr/employees/" + employeeB + "/skills"), "{\"skills\":[],\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/hr/employees/" + employeeB + "/leave-adjustments"), "{\"typeId\":\""
                + leaveTypeA() + "\",\"year\":2027,\"days\":1,\"reason\":\"x\"}")).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/hr/departments/" + departmentB), "{\"name\":\"X\",\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, post("/api/v1/hr/departments/" + departmentB + "/archive")).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/hr/skills/" + skillB), "{\"name\":\"X\",\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/hr/job-roles/" + jobRoleB)).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/hr/onboarding-templates/" + templateB), "{\"name\":\"X\",\"items\":[{\"title\":\"T\","
                + "\"owner\":\"HR\",\"dueOffsetDays\":0}],\"version\":0}")).andExpect(status().isNotFound());
        as(ownerA, json(put("/api/v1/hr/leave-types/" + leaveTypeB), "{\"name\":\"X\",\"paid\":true,\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/hr/leave-requests/" + leaveRequestB + "/approve"), "{\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/hr/leave-requests/" + leaveRequestB + "/cancel"), "{\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/hr/courses/" + courseB)).andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/hr/training-assignments/" + assignmentB + "/complete"), "{\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/hr/policies/" + policyB)).andExpect(status().isNotFound());
        as(ownerA, json(post("/api/v1/hr/policies/" + policyB + "/publish"), "{\"version\":0}"))
                .andExpect(status().isNotFound());
        as(ownerA, get("/api/v1/activities").param("subjectType", "EMPLOYEE").param("subjectId", employeeB.toString()))
                .andExpect(status().isNotFound());
        // body references → 400 on the field
        hireA("\"personId\":\"" + personB + "\"").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("personId"));
        hireA("\"person\":{\"firstName\":\"A\",\"lastName\":\"B\"},\"departmentId\":\"" + departmentB + "\"")
                .andExpect(jsonPath("$.errors[0].field").value("departmentId"));
        hireA("\"person\":{\"firstName\":\"A\",\"lastName\":\"B\"},\"jobRoleId\":\"" + jobRoleB + "\"")
                .andExpect(jsonPath("$.errors[0].field").value("jobRoleId"));
        hireA("\"person\":{\"firstName\":\"A\",\"lastName\":\"B\"},\"managerId\":\"" + employeeB + "\"")
                .andExpect(jsonPath("$.errors[0].field").value("managerId"));
        as(ownerA, json(post("/api/v1/hr/job-roles"), "{\"title\":\"R\",\"requiredSkills\":[{\"skillId\":\"" + skillB
                + "\",\"minimumLevel\":1}]}")).andExpect(jsonPath("$.errors[0].field").value("requiredSkills[0].skillId"));
        as(ownerA, json(post("/api/v1/hr/leave-requests"), "{\"employeeId\":\"" + employeeB + "\",\"typeId\":\""
                + leaveTypeA() + "\",\"startDate\":\"2027-03-01\",\"endDate\":\"2027-03-01\"}"))
                .andExpect(jsonPath("$.errors[0].field").value("employeeId"));
        as(ownerA, json(post("/api/v1/hr/training-assignments"), "{\"courseId\":\"" + courseB + "\",\"employeeId\":\""
                + employeeB + "\"}")).andExpect(jsonPath("$.errors[0].field").value("courseId"));
        // lists, search and the dashboard never show B's rows
        as(ownerA, get("/api/v1/hr/employees")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/hr/leave-requests")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/hr/policies")).andExpect(jsonPath("$.total").value(0));
        as(ownerA, get("/api/v1/search").param("q", "beta")).andExpect(jsonPath("$[*].type",
                Matchers.not(Matchers.hasItem("EMPLOYEE"))));
        as(ownerA, get("/api/v1/hr/dashboard")).andExpect(jsonPath("$.activeHeadcount").value(0));
        // tenant B is untouched
        as(ownerB, get("/api/v1/hr/employees/" + employeeB)).andExpect(jsonPath("$.status").value("ACTIVE"));
    }
```

Add these helpers to `CrossTenantApiIT`:
- **`hireA(String fields)`:** posts `{"employeeNumber":"A-<counter>","startDate":"2026-01-05",` + fields + `}` to `/api/v1/hr/employees` as `ownerA`.
- **`leaveTypeA()`:** reads A's seeded "Annual leave" id.

Name B's person `Beta Person`, so the search check has something to miss.

Add the HRMS paths to `OpenApiContractIT`'s `contains(...)` list, each quoted like the existing entries:
- `/api/v1/hr/employees`, `/api/v1/hr/employees/{id}`, `/api/v1/hr/employees/{id}/end`, `/api/v1/hr/employees/{id}/reinstate`, `/api/v1/hr/employees/{id}/skills`, `/api/v1/hr/employees/{id}/skill-gap`, `/api/v1/hr/employees/{id}/onboarding`, `/api/v1/hr/employees/{id}/leave-balances`, `/api/v1/hr/employees/{id}/leave-adjustments`;
- `/api/v1/hr/departments`, `/api/v1/hr/skills`, `/api/v1/hr/job-roles`, `/api/v1/hr/job-roles/{id}`, `/api/v1/hr/onboarding-templates`;
- `/api/v1/hr/leave-types`, `/api/v1/hr/holidays`, `/api/v1/hr/holidays/{id}`, `/api/v1/hr/leave-requests`, `/api/v1/hr/leave-requests/{id}/approve`, `/api/v1/hr/leave-requests/{id}/reject`, `/api/v1/hr/leave-requests/{id}/cancel`, `/api/v1/hr/leave/away`;
- `/api/v1/hr/courses`, `/api/v1/hr/courses/{id}`, `/api/v1/hr/training-assignments`, `/api/v1/hr/training-assignments/{id}/complete`;
- `/api/v1/hr/policies`, `/api/v1/hr/policies/{id}`, `/api/v1/hr/policies/{id}/publish`;
- `/api/v1/hr/me`, `/api/v1/hr/me/leave-requests`, `/api/v1/hr/me/training/{id}/complete`;
- `/api/v1/hr/dashboard`.

Also assert that `components.schemas` contains `"HrDashboardView"` next to the existing `LeadStats`/`HelpDeskDashboardView` check.

Add to `collaboration/SearchApiIT.java`:

```java
    @Test
    void findsEmployeesByNameOrNumber() throws Exception {
        TestHr.enable(owner);
        TestHr.hire(owner, "Zephyra", "Nair", "ZN-42");
        owner.get("/api/v1/search?q=zephyra").andExpect(jsonPath("$[*].type").value(Matchers.hasItem("EMPLOYEE")))
                .andExpect(jsonPath("$[?(@.type == 'EMPLOYEE')].label").value(Matchers.contains("Zephyra Nair · ZN-42")));
        owner.get("/api/v1/search?q=zn-42").andExpect(jsonPath("$[?(@.type == 'EMPLOYEE')].label")
                .value(Matchers.contains("Zephyra Nair · ZN-42")));
    }
```

The person also appears as a `PARTY` hit, which is why the test filters by type. Each `SearchApiIT` test signs up its own workspace, so enabling HRMS stays within the FREE plan's two modules.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd backend && ./gradlew test --tests '*HrDashboardIT' --tests '*SearchApiIT'`
Expected: FAIL — `/hr/dashboard` is 404 and search returns no EMPLOYEE. (`HrRlsIT`, the gate and cross-tenant tests may already pass; they prove existing behaviour.)

- [ ] **Step 3: Dashboard**

```java
package com.nexusops.hrms;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/** D19. */
@Schema(name = "HrDashboardView")
public record HrDashboardView(long activeHeadcount, List<DepartmentCount> byDepartment, long upcomingStarts,
        long joinersLast90Days, long leaversLast90Days, List<AwayView> awayToday, List<AwayView> awayNext7Days,
        long pendingLeaveRequests, long overdueTrainings, long onboardingInProgress, List<SkillGapCount> topSkillGaps) {

    @Schema(name = "HrDepartmentCount")
    public record DepartmentCount(DepartmentRef department, long employees) {}

    @Schema(name = "HrSkillGapCount")
    public record SkillGapCount(SkillRef skill, long employees) {}
}
```

Add to `HrQueries`:

```java
    record Counts(long active, long upcoming, long joiners, long leavers) {}

    Counts counts(LocalDate today) {
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", TenantContext.requireTenantId())
                .addValue("today", today).addValue("in30", today.plusDays(30)).addValue("ago90", today.minusDays(90));
        Map<String, Object> row = jdbc.queryForMap("select "
                + "count(*) filter (where " + ACTIVE + ") as active, "
                + "count(*) filter (where " + UPCOMING + " and e.start_date <= :in30) as upcoming, "
                + "count(*) filter (where e.start_date between :ago90 and :today) as joiners, "
                + "count(*) filter (where e.end_date >= :ago90 and e.end_date < :today) as leavers "
                + "from employees e where e.tenant_id = :tenant", p);
        return new Counts(((Number) row.get("active")).longValue(), ((Number) row.get("upcoming")).longValue(),
                ((Number) row.get("joiners")).longValue(), ((Number) row.get("leavers")).longValue());
    }

    /** Active employees per department; the null department last. */
    List<Map<String, Object>> activeByDepartment(LocalDate today) {
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", TenantContext.requireTenantId())
                .addValue("today", today);
        return jdbc.queryForList("select e.department_id as department_id, count(*) as n from employees e "
                + "where e.tenant_id = :tenant and " + ACTIVE + " group by e.department_id "
                + "order by e.department_id is null, count(*) desc", p);
    }

    /** Skills most often below their job role's minimum among active employees (D8, D19). */
    List<Map<String, Object>> topSkillGaps(LocalDate today, int limit) {
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", TenantContext.requireTenantId())
                .addValue("today", today).addValue("limit", limit);
        return jdbc.queryForList("""
                select r.skill_id as skill_id, count(*) as n
                from employees e
                join job_role_skills r on r.tenant_id = e.tenant_id and r.job_role_id = e.job_role_id
                left join employee_skills s on s.tenant_id = e.tenant_id and s.employee_id = e.id and s.skill_id = r.skill_id
                where e.tenant_id = :tenant and %s and (s.level is null or s.level < r.minimum_level)
                group by r.skill_id
                order by count(*) desc, r.skill_id
                limit :limit
                """.formatted(ACTIVE), p);
    }

    /** Onboardings with at least one task not done yet. */
    long onboardingInProgress() {
        MapSqlParameterSource p = new MapSqlParameterSource("tenant", TenantContext.requireTenantId());
        Long n = jdbc.queryForObject("""
                select count(distinct o.id) from employee_onboardings o
                join employee_onboarding_tasks l on l.tenant_id = o.tenant_id and l.onboarding_id = o.id
                join tasks t on t.id = l.task_id and t.tenant_id = :tenant
                where o.tenant_id = :tenant and t.status not in ('DONE', 'CANCELLED')
                """, p, Long.class);
        return n == null ? 0 : n;
    }
```

(imports `java.util.Map`.)

`hrms/HrDashboardService.java`. It is a separate service because `LeaveService` and `TrainingService` both depend on `EmployeeService`:

```java
package com.nexusops.hrms;

import com.nexusops.hrms.domain.Department;
import com.nexusops.hrms.domain.Skill;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** D19: the workforce at a glance. */
@Service
public class HrDashboardService {

    private final HrQueries queries;
    private final HrClock clock;
    private final DepartmentService departments;
    private final SkillService skills;
    private final LeaveService leave;
    private final TrainingService training;

    HrDashboardService(HrQueries queries, HrClock clock, DepartmentService departments, SkillService skills,
            LeaveService leave, TrainingService training) {
        this.queries = queries;
        this.clock = clock;
        this.departments = departments;
        this.skills = skills;
        this.leave = leave;
        this.training = training;
    }

    @Transactional(readOnly = true)
    public HrDashboardView dashboard() {
        LocalDate today = clock.today();
        HrQueries.Counts c = queries.counts(today);
        List<Map<String, Object>> depts = queries.activeByDepartment(today);
        Map<UUID, Department> deptNames = departments.byIds(depts.stream().map(r -> (UUID) r.get("department_id"))
                .filter(Objects::nonNull).toList());
        List<Map<String, Object>> gaps = queries.topSkillGaps(today, 5);
        Map<UUID, Skill> skillNames = skills.byIds(gaps.stream().map(r -> (UUID) r.get("skill_id")).toList());
        return new HrDashboardView(c.active(),
                depts.stream().map(r -> new HrDashboardView.DepartmentCount(r.get("department_id") == null ? null
                        : DepartmentService.ref(deptNames.get((UUID) r.get("department_id"))),
                        ((Number) r.get("n")).longValue())).toList(),
                c.upcoming(), c.joiners(), c.leavers(),
                leave.away(today, today), leave.away(today.plusDays(1), today.plusDays(7)),
                leave.pendingCount(), training.overdueCount(), queries.onboardingInProgress(),
                gaps.stream().map(r -> new HrDashboardView.SkillGapCount(
                        SkillService.ref(skillNames.get((UUID) r.get("skill_id"))), ((Number) r.get("n")).longValue()))
                        .toList());
    }
}
```

`hrms/web/HrDashboardController.java` maps `GET /api/v1/hr/dashboard` with `@PreAuthorize("hasAuthority('hr.employee.read')")` and returns `dashboard.dashboard()`.

- [ ] **Step 4: Search order**

In `collaboration/SearchService.java`:

```java
    static final List<String> ORDER = List.of("PARTY", "LEAD", "OPPORTUNITY", "PRODUCT", "PURCHASE_ORDER",
            "SALES_ORDER", "TICKET", "KB_ARTICLE", "EMPLOYEE");
```

- [ ] **Step 5: ADR-0013**

`docs/decisions/0013-hrms-employment-and-leave.md`:

```markdown
# ADR-0013: HRMS — employment beside the directory person, leave in stored working days

- Status: Accepted
- Date: 2026-10-09
- Spec: docs/superpowers/specs/2026-10-09-hrms-mvp-design.md

## Context
Phase 8 adds HR. Blueprint §2.4: HRMS products become record systems rather than operational ones; NexusOps should
connect employee → department → role → skills → tasks → training → leave → documents. The directory already holds every
person and marks employees with an EMPLOYEE role (Phase 4). AI must never decide employment outcomes.

## Decision
1. **An employee is the directory person plus an employment record** (`employees`): number, department, job role,
   manager, type, start/end dates and an optional linked team member. HRMS keeps the person's EMPLOYEE role in step
   through `PartyService.recordEmployment`; a directory SPI (`EmploymentGuard`) refuses manual edits of that role while
   HRMS manages it. `directory` doesn't depend on `hrms`.
2. **Status is derived, not stored**: upcoming, active or left, from the dates in the workspace's time zone (`HrClock`);
   SQL fragments in `HrQueries` mirror the Java rule.
3. **The chain is connected**: a job role lists required skills; an employee's gaps are the deterministic difference
   between required and held levels; courses teaching a skill at a sufficient level are recommended; completing a
   course raises the employee's skills. Hiring can start an onboarding template, which creates collaboration tasks for
   HR, the manager and the new hire.
4. **Leave is counted when requested**: working days (Mon–Fri minus the workspace's holidays) are computed by a pure
   function and stored on the request, so editing holidays later doesn't change decided requests. Balances are sums per
   calendar year: allowance + append-only adjustments − approved − pending. Approvers can't decide their own requests.
   Ending employment cancels only leave and training after the last day.
5. **Self-service** routes act only on the employee linked to the caller.
6. Every HRMS permission belongs to module HRMS. Policies are a full-text-searchable library (the deterministic
   baseline for a later AI policy assistant).

## Consequences
- One person, one contact record; HR data stays in its module.
- A type's current allowance applies to every year (no history of allowances); HR adjusts balances by hand when needed.
- Payroll, performance, attendance, recruitment, accruals, carry-over and half days are later work (spec §1 non-goals).
```

- [ ] **Step 6: Re-export the OpenAPI document, run everything, commit**

Run: `cd backend && ./gradlew test --tests '*OpenApiContractIT' -Dopenapi.export=true`. Then check `git diff docs/api/openapi.json`:
- it shows HRMS additions;
- no existing schema is removed or changed;
- the CRM and HelpDesk dashboard schemas are untouched.

Run: `cd backend && ./gradlew test --tests '*HrDashboardIT' --tests '*HrRlsIT' --tests '*HrModuleGateIT' --tests '*CrossTenantApiIT' --tests '*OpenApiContractIT' --tests '*SearchApiIT' --tests '*RlsCoverageIT' --tests '*ModularityTest' --tests '*EndpointAuthorizationCoverageTest'` — Expected: PASS.

Run: `cd backend && ./gradlew test` — Expected: PASS.

```bash
git add backend/src docs/decisions/0013-hrms-employment-and-leave.md docs/api/openapi.json
git commit -m "feat(hrms): workforce dashboard, employee search; isolation, module gate and cross-tenant proofs; ADR-0013"
```

---

### Task 8: Frontend foundations — types, permissions, the HRMS area and its overview, Settings → HRMS

**Files:**
- Modify: `frontend/src/lib/api/types.ts`, `frontend/src/features/auth/permissions.tsx`, `frontend/src/test/records.ts`, `frontend/src/features/records/SubjectLink.tsx`, `frontend/src/features/shell/GlobalSearch.tsx`, `frontend/src/features/shell/routes.tsx`, `frontend/src/features/shell/nav.ts`, `frontend/src/features/shell/ComingSoonPage.test.tsx`
- Create: `frontend/src/features/hrms/labels.ts`, `labels.test.ts`, `invalidation.ts`, `HrLayout.tsx`, `HrLayout.test.tsx`, `HrIndex.tsx`, `HrOverviewPage.tsx`, `HrOverviewPage.test.tsx`, `routes.tsx`, `SkillLevelSelect.tsx`
- Create: `frontend/src/features/settings/HrSettingsPage.tsx`, `HrSettingsPage.test.tsx`, `frontend/src/features/settings/hr/DepartmentsSection.tsx`, `SkillsSection.tsx`, `JobRolesSection.tsx`, `LeaveTypesSection.tsx`, `HolidaysSection.tsx`, `OnboardingTemplatesSection.tsx`

**Interfaces:**
- Consumes (backend Tasks 1–7): every `/api/v1/hr/**` route and JSON shape. Java records become the TS interfaces below field for field; `Instant`/`LocalDate` are ISO strings; null fields may be absent; `BigDecimal` is a number.
- Produces (later frontend tasks rely on these exact names):
  - **Types:**
    - enums `EmploymentType`, `EmploymentStatus`, `OnboardingOwner`, `LeaveStatus`, `TrainingStatus`, `PolicyStatus`;
    - refs `DepartmentRef`, `SkillRef`, `JobRoleRef`, `EmployeeRef`, `PersonRef`, `CourseRef`, `LeaveTypeRef`;
    - views `SkillView`, `DepartmentView`, `RequiredSkillView`, `JobRoleView`, `EmployeeSkillView`, `OnboardingProgress`, `EmployeeView`, `EmployeeSummary`, `SkillGapView`, `OnboardingItemView`, `OnboardingTemplateView`, `LeaveTypeView`, `HolidayView`, `LeaveRequestView`, `LeaveBalanceView`, `AwayView`, `TaughtSkillView`, `CourseView`, `TrainingAssignmentView`, `PolicyView`, `PolicySummary`, `MeView`, `HrDashboard`;
    - `SubjectType` gains `'EMPLOYEE'`.
  - **`PERMISSIONS`:** `hrEmployeeRead` (`hr.employee.read`), `hrEmployeeUpdate`, `hrSettings`, `hrLeaveApprove`, `hrTraining`, `hrPolicy`, `hrSelf`.
  - **Fixtures:**
    - `aSkill`, `aDepartment`, `aJobRole`, `anEmployee`, `anEmployeeSummary`, `aSkillGap`;
    - `anOnboardingTemplate`, `aLeaveType`, `defaultLeaveTypes`, `aHoliday`, `aLeaveRequest`, `aLeaveBalance`, `anAway`;
    - `aCourse`, `aTrainingAssignment`, `aPolicy`, `aPolicySummary`, `aMe`, `anHrDashboard`.
  - **`features/hrms/labels.ts`:** `EMPLOYMENT_TYPE_LABELS`, `EMPLOYMENT_STATUS_LABELS`, `ONBOARDING_OWNER_LABELS`, `LEAVE_STATUS_LABELS`, `TRAINING_STATUS_LABELS`, `POLICY_STATUS_LABELS`, `SKILL_LEVEL_LABELS` (1 Aware … 5 Expert), `formatDays(n: number | null | undefined): string` ("2.5 days", "1 day", "Unlimited" for null/undefined).
  - **`SkillLevelSelect`:** `({ id, label, value, onChange, error? })`, where `value` is a `''`-able string of `'1'..'5'`.
  - **`invalidateHr(queryClient)`:** every HRMS query key starts with `'hr'`:
    - `['hr', 'dashboard']`;
    - `['hr', 'skills', archived]`, `['hr', 'departments', archived]`, `['hr', 'job-roles', archived]`, `['hr', 'job-role', id]`;
    - `['hr', 'employees', filters]`, `['hr', 'employee', id]`, `['hr', 'skill-gap', id]`, `['hr', 'balances', id, year]`;
    - `['hr', 'onboarding-templates', archived]`, `['hr', 'leave-types', archived]`, `['hr', 'holidays', year]`;
    - `['hr', 'leave-requests', filters]`, `['hr', 'away', from, to]`;
    - `['hr', 'courses', archived]`, `['hr', 'course', id]`, `['hr', 'training', filters]`;
    - `['hr', 'policies', filters]`, `['hr', 'policy', id]`, `['hr', 'me']`.
  - **Area and routes:**
    - `HrLayout` with `TABS`;
    - `hrmsChildren: RouteObject[]` in `features/hrms/routes.tsx`, which later tasks extend;
    - area base path `/app/hrms`.

- [ ] **Step 1: Types**

Append to `frontend/src/lib/api/types.ts`:

```ts
export type EmploymentType = 'FULL_TIME' | 'PART_TIME' | 'CONTRACT' | 'INTERN'
export type EmploymentStatus = 'UPCOMING' | 'ACTIVE' | 'LEFT'
export type OnboardingOwner = 'HR' | 'MANAGER' | 'EMPLOYEE'
export type LeaveStatus = 'PENDING' | 'APPROVED' | 'REJECTED' | 'CANCELLED'
export type TrainingStatus = 'ASSIGNED' | 'COMPLETED' | 'CANCELLED'
export type PolicyStatus = 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'

export interface DepartmentRef { id: string; name: string }
export interface SkillRef { id: string; name: string }
export interface JobRoleRef { id: string; title: string }
export interface EmployeeRef { id: string; name: string; employeeNumber: string }
export interface PersonRef { id: string; name: string; email: string | null; phone: string | null }
export interface CourseRef { id: string; title: string }
export interface LeaveTypeRef { id: string; name: string }

export interface SkillView { id: string; name: string; archivedAt: string | null; version: number }
export interface DepartmentView {
  id: string
  name: string
  description: string | null
  archivedAt: string | null
  version: number
}
export interface RequiredSkillView { skillId: string; name: string; minimumLevel: number }
export interface JobRoleView {
  id: string
  title: string
  department: DepartmentRef | null
  description: string | null
  requiredSkills: RequiredSkillView[]
  archivedAt: string | null
  version: number
}
export interface EmployeeSkillView { skillId: string; name: string; level: number; note: string | null }
export interface OnboardingProgress { templateId: string; templateName: string; done: number; total: number }
export interface EmployeeView {
  id: string
  person: PersonRef
  employeeNumber: string
  department: DepartmentRef | null
  jobRole: JobRoleRef | null
  manager: EmployeeRef | null
  employmentType: EmploymentType
  startDate: string
  endDate: string | null
  endReason: string | null
  status: EmploymentStatus
  user: MemberRef | null
  skills: EmployeeSkillView[]
  onboarding: OnboardingProgress | null
  createdAt: string
  updatedAt: string
  version: number
}
export interface EmployeeSummary {
  id: string
  name: string
  employeeNumber: string
  department: DepartmentRef | null
  jobRole: JobRoleRef | null
  manager: EmployeeRef | null
  status: EmploymentStatus
  startDate: string
  endDate: string | null
}
export interface SkillGapView {
  employee: EmployeeRef
  jobRole: JobRoleRef | null
  gaps: Array<{ skill: SkillRef; required: number; held: number | null; recommendedCourses: CourseRef[] }>
}
export interface OnboardingItemView {
  title: string
  description: string | null
  owner: OnboardingOwner
  dueOffsetDays: number
}
export interface OnboardingTemplateView {
  id: string
  name: string
  items: OnboardingItemView[]
  archivedAt: string | null
  version: number
}
export interface LeaveTypeView {
  id: string
  name: string
  paid: boolean
  annualAllowance: number | null
  archivedAt: string | null
  version: number
}
export interface HolidayView { id: string; date: string; name: string }
export interface LeaveRequestView {
  id: string
  employee: EmployeeRef
  type: LeaveTypeRef
  startDate: string
  endDate: string
  days: number
  reason: string | null
  status: LeaveStatus
  requestedBy: MemberRef | null
  requestedAt: string
  decidedBy: MemberRef | null
  decidedAt: string | null
  decisionNote: string | null
  cancelledAt: string | null
  version: number
}
export interface LeaveBalanceView {
  type: LeaveTypeRef
  paid: boolean
  entitlement: number | null
  taken: number
  pending: number
  available: number | null
}
export interface AwayView { employee: EmployeeRef; type: LeaveTypeRef; startDate: string; endDate: string }
export interface TaughtSkillView { skillId: string; name: string; level: number }
export interface CourseView {
  id: string
  title: string
  description: string | null
  durationHours: number | null
  link: string | null
  skills: TaughtSkillView[]
  archivedAt: string | null
  version: number
}
export interface TrainingAssignmentView {
  id: string
  course: CourseRef
  employee: EmployeeRef
  dueOn: string | null
  status: TrainingStatus
  overdue: boolean
  assignedBy: MemberRef | null
  assignedAt: string
  completedOn: string | null
  completionNote: string | null
  version: number
}
export interface PolicyView {
  id: string
  title: string
  body: string
  status: PolicyStatus
  author: MemberRef | null
  publishedAt: string | null
  createdAt: string
  updatedAt: string
  version: number
}
export interface PolicySummary {
  id: string
  title: string
  excerpt: string
  status: PolicyStatus
  publishedAt: string | null
  updatedAt: string
}
export interface MeView {
  employee: EmployeeView
  year: number
  balances: LeaveBalanceView[]
  requests: LeaveRequestView[]
  trainings: TrainingAssignmentView[]
}
export interface HrDashboard {
  activeHeadcount: number
  byDepartment: Array<{ department: DepartmentRef | null; employees: number }>
  upcomingStarts: number
  joinersLast90Days: number
  leaversLast90Days: number
  awayToday: AwayView[]
  awayNext7Days: AwayView[]
  pendingLeaveRequests: number
  overdueTrainings: number
  onboardingInProgress: number
  topSkillGaps: Array<{ skill: SkillRef; employees: number }>
}
```

Add `| 'EMPLOYEE'` to `SubjectType`. Run `cd frontend && npx prettier --write src/lib/api/types.ts` (prettier expands the one-line interfaces).

- [ ] **Step 2: Permissions, subject paths, search labels, nav, settings tab**

In `PERMISSIONS` (after `articleManage`):

```ts
  hrEmployeeRead: 'hr.employee.read',
  hrEmployeeUpdate: 'hr.employee.update',
  hrSettings: 'hr.settings.manage',
  hrLeaveApprove: 'hr.leave.approve',
  hrTraining: 'hr.training.manage',
  hrPolicy: 'hr.policy.manage',
  hrSelf: 'hr.self.service',
```

and update the doc comment's catalog list to `(V3, V9–V28 catalog)`.

- `SubjectLink.tsx`'s `subjectPath`: `if (type === 'EMPLOYEE') return \`/app/hrms/employees/${id}\``.
- `GlobalSearch.tsx`'s `TYPE_LABELS`: `EMPLOYEE: 'Employee',`.
- `nav.ts`'s `SETTINGS_TABS`: append `{ to: '/app/settings/hrms', label: 'HRMS', anyOf: [PERMISSIONS.hrSettings] }`.
- `nav.ts`'s `MODULE_PHASES.HRMS.description`: `'Employees, leave, onboarding and training.'` (the existing entry stays for the "not enabled" page).

- [ ] **Step 3: Fixtures**

Add to `frontend/src/test/records.ts` (with the type names in its `import type` list):

```ts
const SALES = { id: 'd-sales', name: 'Sales' }
const NEGOTIATION = { id: 's-neg', name: 'Negotiation' }
const EXECUTIVE = { id: 'jr-exec', title: 'Account executive' }
const MEERA_EMP = { id: 'e-meera', name: 'Meera Iyer', employeeNumber: 'E-1' }
const ANNUAL = { id: 'lt-annual', name: 'Annual leave' }

export function aSkill(overrides: Partial<SkillView> = {}): SkillView {
  return { id: 's-neg', name: 'Negotiation', archivedAt: null, version: 0, ...overrides }
}

export function aDepartment(overrides: Partial<DepartmentView> = {}): DepartmentView {
  return { id: 'd-sales', name: 'Sales', description: null, archivedAt: null, version: 0, ...overrides }
}

export function aJobRole(overrides: Partial<JobRoleView> = {}): JobRoleView {
  return {
    id: 'jr-exec',
    title: 'Account executive',
    department: SALES,
    description: null,
    requiredSkills: [{ skillId: 's-neg', name: 'Negotiation', minimumLevel: 4 }],
    archivedAt: null,
    version: 0,
    ...overrides,
  }
}

export function anEmployee(overrides: Partial<EmployeeView> = {}): EmployeeView {
  return {
    id: 'e-meera',
    person: { id: 'p-meera', name: 'Meera Iyer', email: 'meera@deccan.test', phone: null },
    employeeNumber: 'E-1',
    department: SALES,
    jobRole: EXECUTIVE,
    manager: null,
    employmentType: 'FULL_TIME',
    startDate: '2026-01-05',
    endDate: null,
    endReason: null,
    status: 'ACTIVE',
    user: null,
    skills: [{ skillId: 's-neg', name: 'Negotiation', level: 2, note: null }],
    onboarding: null,
    createdAt: '2026-01-05T09:00:00Z',
    updatedAt: '2026-01-05T09:00:00Z',
    version: 0,
    ...overrides,
  }
}

export function anEmployeeSummary(overrides: Partial<EmployeeSummary> = {}): EmployeeSummary {
  return {
    id: 'e-meera',
    name: 'Meera Iyer',
    employeeNumber: 'E-1',
    department: SALES,
    jobRole: EXECUTIVE,
    manager: null,
    status: 'ACTIVE',
    startDate: '2026-01-05',
    endDate: null,
    ...overrides,
  }
}

export function aSkillGap(overrides: Partial<SkillGapView> = {}): SkillGapView {
  return {
    employee: MEERA_EMP,
    jobRole: EXECUTIVE,
    gaps: [
      {
        skill: NEGOTIATION,
        required: 4,
        held: 2,
        recommendedCourses: [{ id: 'c-adv', title: 'Advanced negotiation' }],
      },
    ],
    ...overrides,
  }
}

export function anOnboardingTemplate(
  overrides: Partial<OnboardingTemplateView> = {},
): OnboardingTemplateView {
  return {
    id: 'ot-std',
    name: 'Standard onboarding',
    items: [
      { title: 'Prepare laptop and accounts', description: null, owner: 'HR', dueOffsetDays: 0 },
      { title: '30-day check-in', description: null, owner: 'MANAGER', dueOffsetDays: 30 },
    ],
    archivedAt: null,
    version: 0,
    ...overrides,
  }
}

export function aLeaveType(overrides: Partial<LeaveTypeView> = {}): LeaveTypeView {
  return {
    id: 'lt-annual',
    name: 'Annual leave',
    paid: true,
    annualAllowance: 20,
    archivedAt: null,
    version: 0,
    ...overrides,
  }
}

export function defaultLeaveTypes(): LeaveTypeView[] {
  return [
    aLeaveType(),
    aLeaveType({ id: 'lt-sick', name: 'Sick leave', annualAllowance: 10 }),
    aLeaveType({ id: 'lt-unpaid', name: 'Unpaid leave', paid: false, annualAllowance: null }),
  ]
}

export function aHoliday(overrides: Partial<HolidayView> = {}): HolidayView {
  return { id: 'h-1', date: '2027-01-26', name: 'Republic Day', ...overrides }
}

export function aLeaveRequest(overrides: Partial<LeaveRequestView> = {}): LeaveRequestView {
  return {
    id: 'lr-1',
    employee: MEERA_EMP,
    type: ANNUAL,
    startDate: '2027-03-01',
    endDate: '2027-03-02',
    days: 2,
    reason: 'Family',
    status: 'PENDING',
    requestedBy: { id: 'u-ada', name: 'Ada Lovelace' },
    requestedAt: '2026-10-09T09:00:00Z',
    decidedBy: null,
    decidedAt: null,
    decisionNote: null,
    cancelledAt: null,
    version: 0,
    ...overrides,
  }
}

export function aLeaveBalance(overrides: Partial<LeaveBalanceView> = {}): LeaveBalanceView {
  return { type: ANNUAL, paid: true, entitlement: 20, taken: 4, pending: 2, available: 14, ...overrides }
}

export function anAway(overrides: Partial<AwayView> = {}): AwayView {
  return { employee: MEERA_EMP, type: ANNUAL, startDate: '2027-03-01', endDate: '2027-03-02', ...overrides }
}

export function aCourse(overrides: Partial<CourseView> = {}): CourseView {
  return {
    id: 'c-adv',
    title: 'Advanced negotiation',
    description: null,
    durationHours: 3.5,
    link: 'https://learn.example/neg',
    skills: [{ skillId: 's-neg', name: 'Negotiation', level: 4 }],
    archivedAt: null,
    version: 0,
    ...overrides,
  }
}

export function aTrainingAssignment(
  overrides: Partial<TrainingAssignmentView> = {},
): TrainingAssignmentView {
  return {
    id: 'ta-1',
    course: { id: 'c-adv', title: 'Advanced negotiation' },
    employee: MEERA_EMP,
    dueOn: '2027-01-31',
    status: 'ASSIGNED',
    overdue: false,
    assignedBy: { id: 'u-ada', name: 'Ada Lovelace' },
    assignedAt: '2026-10-09T09:00:00Z',
    completedOn: null,
    completionNote: null,
    version: 0,
    ...overrides,
  }
}

export function aPolicy(overrides: Partial<PolicyView> = {}): PolicyView {
  return {
    id: 'pol-1',
    title: 'Leave policy',
    body: 'Annual leave is 20 days a year.',
    status: 'PUBLISHED',
    author: { id: 'u-ada', name: 'Ada Lovelace' },
    publishedAt: '2026-10-08T09:00:00Z',
    createdAt: '2026-10-08T08:00:00Z',
    updatedAt: '2026-10-08T09:00:00Z',
    version: 1,
    ...overrides,
  }
}

export function aPolicySummary(overrides: Partial<PolicySummary> = {}): PolicySummary {
  return {
    id: 'pol-1',
    title: 'Leave policy',
    excerpt: 'Annual leave is 20 days a year.',
    status: 'PUBLISHED',
    publishedAt: '2026-10-08T09:00:00Z',
    updatedAt: '2026-10-08T09:00:00Z',
    ...overrides,
  }
}

export function aMe(overrides: Partial<MeView> = {}): MeView {
  return {
    employee: anEmployee({ user: { id: 'u-ada', name: 'Ada Lovelace' } }),
    year: 2027,
    balances: [aLeaveBalance()],
    requests: [aLeaveRequest()],
    trainings: [aTrainingAssignment()],
    ...overrides,
  }
}

export function anHrDashboard(overrides: Partial<HrDashboard> = {}): HrDashboard {
  return {
    activeHeadcount: 12,
    byDepartment: [
      { department: SALES, employees: 7 },
      { department: null, employees: 5 },
    ],
    upcomingStarts: 2,
    joinersLast90Days: 3,
    leaversLast90Days: 1,
    awayToday: [anAway()],
    awayNext7Days: [],
    pendingLeaveRequests: 4,
    overdueTrainings: 1,
    onboardingInProgress: 2,
    topSkillGaps: [{ skill: NEGOTIATION, employees: 3 }],
    ...overrides,
  }
}
```

- [ ] **Step 4: Write the failing tests**

`frontend/src/features/hrms/labels.test.ts`:

```ts
import { describe, expect, it } from 'vitest'
import { formatDays, SKILL_LEVEL_LABELS } from './labels'

describe('formatDays', () => {
  it('says day or days and keeps halves', () => {
    expect(formatDays(1)).toBe('1 day')
    expect(formatDays(2)).toBe('2 days')
    expect(formatDays(2.5)).toBe('2.5 days')
    expect(formatDays(0)).toBe('0 days')
    expect(formatDays(-1)).toBe('-1 days')
  })

  it('shows Unlimited without a number', () => {
    expect(formatDays(null)).toBe('Unlimited')
    expect(formatDays(undefined)).toBe('Unlimited')
  })
})

describe('SKILL_LEVEL_LABELS', () => {
  it('names the five levels', () => {
    expect(SKILL_LEVEL_LABELS[1]).toBe('1 · Aware')
    expect(SKILL_LEVEL_LABELS[5]).toBe('5 · Expert')
  })
})
```

`frontend/src/features/hrms/HrLayout.test.tsx` mirrors `features/helpdesk/HelpDeskLayout.test.tsx`, with module `HRMS`, path `/app/hrms` and navigation name `HRMS`:
1. **`explains that HRMS is off when the module is disabled`** — text `'HRMS is not enabled for this workspace.'`.
2. **`shows the sections the user may open`** — all permissions, `GET /hr/dashboard` → `anHrDashboard()`; the nav has `Overview`, `Employees`, `Leave`, `Training`, `Policies` and `My HR`.
3. **`has no sections without any HRMS permission`** — every permission except `hr.*` → "You don't have access to this page".
4. **`sends a self-service user to My HR`** — permissions `['hr.self.service']`, `GET /hr/me` → `aMe()`; expects the heading `My HR` and the path `/app/hrms/me`. **This case is added in Task 11**, together with the page it opens; Task 8 writes cases 1–3.

`frontend/src/features/hrms/HrOverviewPage.test.tsx`:

```tsx
import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { anHrDashboard } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(dashboard = anHrDashboard()) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'] })).on('GET /hr/dashboard', { body: dashboard })
  return renderApp({ server, path: '/app/hrms' })
}

describe('HrOverviewPage', () => {
  it('shows headcount, movement and what needs attention', async () => {
    setup()
    const people = await screen.findByRole('region', { name: 'People' })
    expect(within(people).getByText('12')).toBeInTheDocument()
    expect(within(people).getByText('Sales')).toBeInTheDocument()
    expect(within(people).getByText('No department')).toBeInTheDocument()
    const attention = screen.getByRole('region', { name: 'Needs attention' })
    expect(within(attention).getByRole('link', { name: /Pending leave requests/ })).toHaveAttribute(
      'href',
      '/app/hrms/leave',
    )
    expect(within(attention).getByRole('link', { name: /Overdue trainings/ })).toHaveAttribute(
      'href',
      '/app/hrms/training?overdue=true',
    )
    const away = screen.getByRole('region', { name: 'Away today' })
    expect(within(away).getByRole('link', { name: 'Meera Iyer' })).toHaveAttribute(
      'href',
      '/app/hrms/employees/e-meera',
    )
    const gaps = screen.getByRole('region', { name: 'Most common skill gaps' })
    expect(within(gaps).getByText('Negotiation')).toBeInTheDocument()
  })

  it('says when nobody is away', async () => {
    setup(anHrDashboard({ awayToday: [], awayNext7Days: [] }))
    const away = await screen.findByRole('region', { name: 'Away today' })
    expect(within(away).getByText('Nobody is away today.')).toBeInTheDocument()
  })
})
```

`frontend/src/features/settings/HrSettingsPage.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aDepartment,
  aHoliday,
  aJobRole,
  anOnboardingTemplate,
  aSkill,
  defaultLeaveTypes,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'] }))
    .on('GET /hr/departments', (r) => ({
      body: r.query.get('archived') === 'true' ? [] : [aDepartment()],
    }))
    .on('GET /hr/skills', (r) => ({
      body:
        r.query.get('archived') === 'true'
          ? []
          : [aSkill({ id: 's-excel', name: 'Excel' }), aSkill()],
    }))
    .on('GET /hr/job-roles', (r) => ({
      body: r.query.get('archived') === 'true' ? [] : [aJobRole()],
    }))
    .on('GET /hr/leave-types', (r) => ({
      body: r.query.get('archived') === 'true' ? [] : defaultLeaveTypes(),
    }))
    .on('GET /hr/holidays', { body: [aHoliday()] })
    .on('GET /hr/onboarding-templates', (r) => ({
      body: r.query.get('archived') === 'true' ? [] : [anOnboardingTemplate()],
    }))
  return renderApp({ server, path: '/app/settings/hrms' })
}

describe('HrSettingsPage', () => {
  it('lists every HR setting', async () => {
    setup()
    expect(await screen.findByRole('region', { name: 'Departments' })).toHaveTextContent('Sales')
    expect(screen.getByRole('region', { name: 'Skills' })).toHaveTextContent('Excel')
    const roles = screen.getByRole('region', { name: 'Job roles' })
    expect(await within(roles).findByText('Account executive')).toBeInTheDocument()
    expect(roles).toHaveTextContent('Negotiation (4)')
    const leave = screen.getByRole('region', { name: 'Leave types' })
    expect(await within(leave).findByText('Unpaid leave')).toBeInTheDocument()
    expect(leave).toHaveTextContent('Unlimited')
    expect(screen.getByRole('region', { name: 'Public holidays' })).toHaveTextContent('Republic Day')
    expect(screen.getByRole('region', { name: 'Onboarding templates' })).toHaveTextContent(
      'Standard onboarding',
    )
  })

  it('creates a job role with required skills', async () => {
    const { server, user } = setup()
    server.on('POST /hr/job-roles', { status: 201, body: aJobRole({ id: 'jr-new' }) })
    const roles = await screen.findByRole('region', { name: 'Job roles' })
    await user.click(within(roles).getByRole('button', { name: 'New job role' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Title'), 'Analyst')
    await user.selectOptions(within(dialog).getByLabelText('Department'), 'd-sales')
    await user.click(within(dialog).getByRole('button', { name: 'Add skill' }))
    await user.selectOptions(within(dialog).getByLabelText('Skill 1'), 's-excel')
    await user.selectOptions(within(dialog).getByLabelText('Skill 1 minimum level'), '3')
    await user.click(within(dialog).getByRole('button', { name: 'Create job role' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/job-roles')[0]?.body).toEqual({
        title: 'Analyst',
        departmentId: 'd-sales',
        description: null,
        requiredSkills: [{ skillId: 's-excel', minimumLevel: 3 }],
      }),
    )
  })

  it('adds a public holiday and removes one', async () => {
    const { server, user } = setup()
    server.on('POST /hr/holidays', {
      status: 201,
      body: aHoliday({ id: 'h-2', date: '2027-08-15', name: 'Independence Day' }),
    })
    server.on('DELETE /hr/holidays/:id', { status: 204 })
    const holidays = await screen.findByRole('region', { name: 'Public holidays' })
    await user.type(within(holidays).getByLabelText('Date'), '2027-08-15')
    await user.type(within(holidays).getByLabelText('Holiday name'), 'Independence Day')
    await user.click(within(holidays).getByRole('button', { name: 'Add holiday' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/holidays')[0]?.body).toEqual({
        date: '2027-08-15',
        name: 'Independence Day',
      }),
    )
    await user.click(within(holidays).getByRole('button', { name: 'Remove Republic Day' }))
    await waitFor(() => expect(server.callsTo('DELETE /hr/holidays/:id')[0]?.params.id).toBe('h-1'))
  })

  it('creates a leave type with an allowance or unlimited', async () => {
    const { server, user } = setup()
    server.on('POST /hr/leave-types', { status: 201, body: defaultLeaveTypes()[0] })
    const leave = await screen.findByRole('region', { name: 'Leave types' })
    await user.click(within(leave).getByRole('button', { name: 'New leave type' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Study leave')
    await user.click(within(dialog).getByLabelText('Paid'))
    await user.click(within(dialog).getByRole('button', { name: 'Create leave type' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/leave-types')[0]?.body).toEqual({
        name: 'Study leave',
        paid: true,
        annualAllowance: null,
      }),
    )
  })

  it('edits an onboarding template item by item', async () => {
    const { server, user } = setup()
    const sections = await screen.findByRole('region', { name: 'Onboarding templates' })
    server.on('PUT /hr/onboarding-templates/:id', { body: anOnboardingTemplate({ version: 1 }) })
    await user.click(within(sections).getByRole('button', { name: 'Edit Standard onboarding' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Remove item 2' }))
    await user.click(within(dialog).getByRole('button', { name: 'Add item' }))
    await user.type(within(dialog).getByLabelText('Item 2 title'), 'Meet the team')
    await user.selectOptions(within(dialog).getByLabelText('Item 2 owner'), 'MANAGER')
    const offset = within(dialog).getByLabelText('Item 2 due (days after start)')
    await user.clear(offset)
    await user.type(offset, '2')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    await waitFor(() =>
      expect(server.callsTo('PUT /hr/onboarding-templates/:id')[0]?.body).toEqual({
        name: 'Standard onboarding',
        items: [
          { title: 'Prepare laptop and accounts', description: null, owner: 'HR', dueOffsetDays: 0 },
          { title: 'Meet the team', description: null, owner: 'MANAGER', dueOffsetDays: 2 },
        ],
        version: 0,
      }),
    )
  })
})
```

Change `frontend/src/features/shell/ComingSoonPage.test.tsx` so the module cases no longer use HRMS (it is no longer "coming soon"):
- the first test ("names the blueprint phase of an enabled module") checks a platform feature instead: path `/app/workflows`, heading `'Workflows'`, `/Phase 9/`;
- the second test ("explains a module that is not enabled") keeps HRMS: path `/app/hrms` with `modules: []` still shows `'HRMS is not enabled for this workspace.'` and the "Manage modules" link, because `HrLayout` renders the same `ComingSoonPage`.

Run: `cd frontend && npx vitest run src/features/hrms src/features/settings/HrSettingsPage.test.tsx src/features/shell/ComingSoonPage.test.tsx`
Expected: FAIL — the HRMS area and settings page don't exist.

- [ ] **Step 5: Labels, level select, invalidation**

`frontend/src/features/hrms/labels.ts`:

```ts
import type {
  EmploymentStatus,
  EmploymentType,
  LeaveStatus,
  OnboardingOwner,
  PolicyStatus,
  TrainingStatus,
} from '@/lib/api/types'

export const EMPLOYMENT_TYPE_LABELS: Record<EmploymentType, string> = {
  FULL_TIME: 'Full time',
  PART_TIME: 'Part time',
  CONTRACT: 'Contract',
  INTERN: 'Intern',
}

export const EMPLOYMENT_STATUS_LABELS: Record<EmploymentStatus, string> = {
  UPCOMING: 'Starting soon',
  ACTIVE: 'Active',
  LEFT: 'Left',
}

export const ONBOARDING_OWNER_LABELS: Record<OnboardingOwner, string> = {
  HR: 'HR',
  MANAGER: 'Manager',
  EMPLOYEE: 'New hire',
}

export const LEAVE_STATUS_LABELS: Record<LeaveStatus, string> = {
  PENDING: 'Pending',
  APPROVED: 'Approved',
  REJECTED: 'Rejected',
  CANCELLED: 'Cancelled',
}

export const TRAINING_STATUS_LABELS: Record<TrainingStatus, string> = {
  ASSIGNED: 'Assigned',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
}

export const POLICY_STATUS_LABELS: Record<PolicyStatus, string> = {
  DRAFT: 'Draft',
  PUBLISHED: 'Published',
  ARCHIVED: 'Archived',
}

export const SKILL_LEVEL_LABELS: Record<number, string> = {
  1: '1 · Aware',
  2: '2 · Basic',
  3: '3 · Competent',
  4: '4 · Proficient',
  5: '5 · Expert',
}

/** 1 → "1 day", 2.5 → "2.5 days", null → "Unlimited" (types without an allowance). */
export function formatDays(n: number | null | undefined): string {
  if (n == null) return 'Unlimited'
  return `${Number(n.toFixed(1))} ${n === 1 ? 'day' : 'days'}`
}
```

`frontend/src/features/hrms/SkillLevelSelect.tsx`:

```tsx
import { describedBy, Field } from '@/components/form/Field'
import { NativeSelect } from '@/components/form/NativeSelect'
import { SKILL_LEVEL_LABELS } from './labels'

/** A skill level 1–5 ('' = not chosen). */
export function SkillLevelSelect({
  id,
  label,
  value,
  onChange,
  error,
}: {
  id: string
  label: string
  value: string
  onChange: (value: string) => void
  error?: string
}) {
  return (
    <Field id={id} label={label} error={error}>
      <NativeSelect
        id={id}
        value={value}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy(id, error)}
        onChange={(e) => onChange(e.target.value)}
      >
        <option value="">Choose…</option>
        {[1, 2, 3, 4, 5].map((n) => (
          <option key={n} value={String(n)}>
            {SKILL_LEVEL_LABELS[n]}
          </option>
        ))}
      </NativeSelect>
    </Field>
  )
}
```

`frontend/src/features/hrms/invalidation.ts`:

```ts
import type { QueryClient } from '@tanstack/react-query'

/** Employees, leave, training and the dashboard depend on each other: any HRMS write refreshes them all. */
export async function invalidateHr(queryClient: QueryClient): Promise<void> {
  await queryClient.invalidateQueries({ queryKey: ['hr'] })
}
```

- [ ] **Step 6: The area, its index and the overview**

`frontend/src/features/hrms/HrLayout.tsx` mirrors `features/helpdesk/HelpDeskLayout.tsx`. Its differences:
- module `HRMS` and `MODULE_PHASES.HRMS`;
- nav `aria-label="HRMS"`;
- these `TABS`:

```ts
export const TABS: Array<{ to: string; label: string; anyOf: PermissionCode[]; end?: boolean }> = [
  { to: '/app/hrms', label: 'Overview', anyOf: [PERMISSIONS.hrEmployeeRead], end: true },
  { to: '/app/hrms/employees', label: 'Employees', anyOf: [PERMISSIONS.hrEmployeeRead] },
  { to: '/app/hrms/leave', label: 'Leave', anyOf: [PERMISSIONS.hrLeaveApprove] },
  { to: '/app/hrms/training', label: 'Training', anyOf: [PERMISSIONS.hrEmployeeRead] },
  { to: '/app/hrms/policies', label: 'Policies', anyOf: [PERMISSIONS.hrSelf, PERMISSIONS.hrPolicy] },
  { to: '/app/hrms/me', label: 'My HR', anyOf: [PERMISSIONS.hrSelf] },
]
```

`HrIndex.tsx` mirrors `HelpDeskIndex.tsx`: it renders `HrOverviewPage` with `hrEmployeeRead`, otherwise it navigates to the first allowed tab.

`frontend/src/features/hrms/HrOverviewPage.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import type { AwayView, HrDashboard } from '@/lib/api/types'
import { formatDate } from '@/lib/format'

function AwayList({ label, people, empty }: { label: string; people: AwayView[]; empty: string }) {
  return (
    <section aria-label={label} className="space-y-3 rounded-lg border p-4">
      <h2 className="font-semibold">{label}</h2>
      {people.length === 0 ? (
        <p className="text-sm text-muted-foreground">{empty}</p>
      ) : (
        <ul className="space-y-1 text-sm">
          {people.map((a) => (
            <li key={`${a.employee.id}-${a.startDate}`} className="flex flex-wrap gap-x-2">
              <Link
                to={`/app/hrms/employees/${a.employee.id}`}
                className="underline-offset-4 hover:underline"
              >
                {a.employee.name}
              </Link>
              <span className="text-muted-foreground">
                {a.type.name} · {formatDate(a.startDate)} – {formatDate(a.endDate)}
              </span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

/** D19: the workforce at a glance. */
export function HrOverviewPage() {
  const api = useApi()
  const dashboard = useQuery({
    queryKey: ['hr', 'dashboard'],
    queryFn: () => api.get<HrDashboard>('/hr/dashboard'),
    refetchOnMount: 'always',
  })
  return (
    <>
      <PageHeader
        title="People overview"
        description="Who works here, who is away, and what needs attention."
      />
      {dashboard.isPending ? (
        <ListSkeleton />
      ) : dashboard.isError ? (
        <ErrorState error={dashboard.error} onRetry={() => void dashboard.refetch()} />
      ) : (
        <div className="grid gap-4 lg:grid-cols-3">
          <section aria-label="People" className="space-y-3 rounded-lg border p-4">
            <h2 className="font-semibold">People</h2>
            <p className="text-3xl font-semibold tabular-nums">{dashboard.data.activeHeadcount}</p>
            <p className="text-sm text-muted-foreground">
              {dashboard.data.upcomingStarts} starting in the next 30 days ·{' '}
              {dashboard.data.joinersLast90Days} joined and {dashboard.data.leaversLast90Days} left
              in the last 90 days
            </p>
            <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 text-sm">
              {dashboard.data.byDepartment.map((d) => (
                <div key={d.department?.id ?? 'none'} className="col-span-2 grid grid-cols-subgrid">
                  <dt className="text-muted-foreground">{d.department?.name ?? 'No department'}</dt>
                  <dd className="text-right tabular-nums">{d.employees}</dd>
                </div>
              ))}
            </dl>
          </section>
          <section aria-label="Needs attention" className="space-y-3 rounded-lg border p-4">
            <h2 className="font-semibold">Needs attention</h2>
            <ul className="space-y-1 text-sm">
              <li>
                <Link to="/app/hrms/leave" className="underline-offset-4 hover:underline">
                  Pending leave requests: {dashboard.data.pendingLeaveRequests}
                </Link>
              </li>
              <li>
                <Link
                  to="/app/hrms/training?overdue=true"
                  className="underline-offset-4 hover:underline"
                >
                  Overdue trainings: {dashboard.data.overdueTrainings}
                </Link>
              </li>
              <li>Onboarding in progress: {dashboard.data.onboardingInProgress}</li>
            </ul>
          </section>
          <section aria-label="Most common skill gaps" className="space-y-3 rounded-lg border p-4">
            <h2 className="font-semibold">Most common skill gaps</h2>
            {dashboard.data.topSkillGaps.length === 0 ? (
              <p className="text-sm text-muted-foreground">
                Everyone meets their job role&apos;s skills.
              </p>
            ) : (
              <dl className="grid grid-cols-[1fr_auto] gap-x-3 gap-y-1 text-sm">
                {dashboard.data.topSkillGaps.map((g) => (
                  <div key={g.skill.id} className="col-span-2 grid grid-cols-subgrid">
                    <dt>{g.skill.name}</dt>
                    <dd className="text-right tabular-nums">
                      {g.employees} {g.employees === 1 ? 'person' : 'people'}
                    </dd>
                  </div>
                ))}
              </dl>
            )}
          </section>
          <AwayList
            label="Away today"
            people={dashboard.data.awayToday}
            empty="Nobody is away today."
          />
          <AwayList
            label="Away in the next 7 days"
            people={dashboard.data.awayNext7Days}
            empty="Nobody is away in the next 7 days."
          />
        </div>
      )}
    </>
  )
}
```

`features/hrms/routes.tsx`:

```tsx
import type { RouteObject } from 'react-router'
import { HrIndex } from './HrIndex'

/** /app/hrms/* children (Tasks 9–11 add theirs). */
export const hrmsChildren: RouteObject[] = [{ index: true, element: <HrIndex /> }]
```

In `features/shell/routes.tsx`:
- replace `modulePage('hrms', 'HRMS'),` with `{ path: 'hrms', element: <HrLayout />, children: hrmsChildren },`;
- add `{ path: 'hrms', element: <RequirePermission anyOf={[PERMISSIONS.hrSettings]}><HrSettingsPage /></RequirePermission> }` to `settingsChildren`, before the catch-all.

If `modulePage` is no longer used anywhere, delete it and its now-unused imports.

- [ ] **Step 7: Settings → HRMS**

`frontend/src/features/settings/HrSettingsPage.tsx` stacks the six sections under one `PageHeader` (title `HRMS`, description "Departments, job roles and the skills they need, leave rules and onboarding checklists."), in this order:
- `DepartmentsSection`
- `JobRolesSection`
- `SkillsSection`
- `LeaveTypesSection`
- `HolidaysSection`
- `OnboardingTemplatesSection`

Each section is a `<section aria-label="…">` with an `h2`. The simple ones mirror `CategoriesSection` in the committed `features/settings/HelpDeskSettingsPage.tsx`: an active plus archived query, a table, a create/edit dialog with RHF + Zod, Archive (`ConfirmDialog`) and Restore, `applyFieldErrors`, and `invalidateHr` instead of `invalidateHelpDesk`.

| Section (aria-label) | Query key / route | Columns | Dialog fields (labels) → body | Buttons (exact names) |
|---|---|---|---|---|
| `Departments` | `['hr','departments',archived]` / `/hr/departments` | Name, Description | Name (≤80, required), Description (≤500) → `{ name, description: '' → null }` | `New department`, `Edit <name>`, `Archive <name>`, `Restore <name>`, submit `Create department` / `Save changes` |
| `Skills` | `['hr','skills',archived]` / `/hr/skills` | Name | Name (≤60) → `{ name }` | `New skill`, `Edit <name>`, `Archive <name>`, `Restore <name>`, submit `Create skill` / `Save changes` |
| `Leave types` | `['hr','leave-types',archived]` / `/hr/leave-types` | Name, Paid (`Paid`/`Unpaid`), Allowance (`formatDays(annualAllowance)`) | Name (≤60), Paid (checkbox, label `Paid`), Annual allowance (days; blank = unlimited; integer 0–366) → `{ name, paid, annualAllowance: '' → null }` | `New leave type`, `Edit <name>`, `Archive <name>`, `Restore <name>`, submit `Create leave type` / `Save changes` |

Updates add `version`. The archive confirmations read:
- departments: "Archive {name}? Departments with employees in them can't be archived.";
- skills: "It can't be added to job roles or people any more. Where it's already used, it stays.";
- leave types: "New requests can't use it. Existing requests and balances keep it."

`HolidaysSection` (aria-label `Public holidays`) is different, with no dialog and no edit:
- **Year select:** label `Year`, the current year plus or minus one, default the current year (`todayIso().slice(0,4)`).
- **List:** `GET /hr/holidays?year=` (key `['hr','holidays',year]`), each row with its date (`formatDate`) and name, plus a `Remove <name>` button → `DELETE /hr/holidays/{id}`.
- **Inline add form:** `Date` (type `date`) and `Holiday name` (≤80) inputs and an `Add holiday` button → `POST /hr/holidays {date, name}`. Show server field errors under the matching input, clear the inputs on success, then `invalidateHr`.

`JobRolesSection` (aria-label `Job roles`):
- **Table:**
  - columns Title, Department (`—` when none) and Required skills, the last formatted `"<name> (<level>)"` joined by `, ` (e.g. `Negotiation (4)`);
  - `Edit`/`Archive`/`Restore` buttons as in the other sections;
  - a `New job role` button.
- **Dialog fields:**
  - `Title` (≤80, required);
  - `Department`: a `NativeSelect` over `GET /hr/departments`, with `''` meaning none (`No department`);
  - `Description` (≤2000);
  - a required-skills editor: rows of `Skill N` (`NativeSelect` over `GET /hr/skills`, `''` = `Choose…`) and `Skill N minimum level` (`SkillLevelSelect`), each with a `Remove skill N` button, and below them an `Add skill` button.
- **Validation:** client-side, every row needs a skill and a level (`"Choose a skill."` / `"Choose a level."` under the field), and a skill may appear only once (`"Each skill can be listed once."` on the later row).
- **Server errors:** map `requiredSkills[i].skillId` and `requiredSkills[i].minimumLevel` to the row fields; map other fields with `applyFieldErrors`.
- **Body:** `{ title, departmentId: '' → null, description: '' → null, requiredSkills: [{ skillId, minimumLevel: Number(level) }] }`, plus `version` on edit.
- **Submit labels:** `Create job role` / `Save changes`.

`OnboardingTemplatesSection` (aria-label `Onboarding templates`):
- **Table:** Name and Items (count); `New template`, `Edit <name>`, `Archive <name>`, `Restore <name>`.
- **Dialog:** `Name` (≤80), then an items editor. Each item `N` has:
  - `Item N title` (≤200);
  - `Item N description` (`Textarea`, ≤2000);
  - `Item N owner`, a `NativeSelect` over `ONBOARDING_OWNER_LABELS` (values `HR`/`MANAGER`/`EMPLOYEE`);
  - `Item N due (days after start)`, a number 0–365;
  - a `Remove item N` button.

  An `Add item` button below appends `{ title: '', description: '', owner: 'HR', dueOffsetDays: '0' }`.
- **Validation:** client-side, at least one item (`"Add at least one item."`), each title required, offset an integer 0–365.
- **Body:** `{ name, items: [{ title, description: '' → null, owner, dueOffsetDays: Number }] }`, plus `version` on edit.
- **Submit labels:** `Create template` / `Save changes`.

The settings test pins the exact names and bodies used above. When the page renders, the "Job roles" table must show `Negotiation (4)`, and "Leave types" must show `Unlimited` for the unpaid type.

- [ ] **Step 8: Run the tests, then the frontend checks**

Run: `cd frontend && npx vitest run src/features/hrms src/features/settings src/features/shell`
Expected: PASS.

Run: `cd frontend && npx prettier --write src && npm run format:check && npm run lint && npm run typecheck && npm test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add frontend/src
git commit -m "feat(hrms-ui): the HRMS area with its overview, and Settings → HRMS"
```

---

### Task 9: Employees — the list, hiring, and the employee page with skills, gaps and onboarding

**Files:**
- Create: `frontend/src/features/hrms/EmployeePicker.tsx`, `MemberSelect.tsx`, `EmployeeFormDialog.tsx`, `EmployeesPage.tsx`, `EmployeesPage.test.tsx`, `EndEmploymentDialog.tsx`, `SkillsDialog.tsx`, `SkillGapCard.tsx`, `OnboardingCard.tsx`, `EmployeeDetailPage.tsx`, `EmployeeDetailPage.test.tsx`, `EmployeeRecordLink.tsx`, `EmployeeRecordLink.test.tsx`
- Modify: `frontend/src/features/hrms/routes.tsx`, `frontend/src/features/directory/PartyDetailPage.tsx`

**Interfaces:**
- Consumes (Task 8): types, `PERMISSIONS.hr*`, labels, `SkillLevelSelect`, `invalidateHr`, fixtures; `PartyPicker` (`features/records/PartyPicker`, `kind="PERSON"`); `SubjectTasksPanel`, `ActivityPanel`, `DocumentsPanel` (`EMPLOYEE`).
- Produces:
  - **Pickers:**
    - `EmployeePicker({ id, label, value, onChange, current?, error?, blankLabel? })` searches `GET /hr/employees?q=&size=20`;
    - `MemberSelect({ id, label, value, onChange, current?, error? })` lists `GET /hr/members?q=`.
  - **`EmployeeFormDialog({ employee?, onClose, onSaved })`:** hire (POST) or edit (PUT with `version`). It defines the module's `employeeKey = (id) => ['hr', 'employee', id] as const`.
  - **`EmployeeDetailPage`** at `/app/hrms/employees/:employeeId`. Task 10 adds a `LeaveCard` and Task 11 a `TrainingCard` into the slots marked `{/* leave */}` and `{/* training */}`.
  - **`EmployeesPage`** at `/app/hrms/employees`, with URL filters `q`, `status` (`''` current | `UPCOMING` | `ACTIVE` | `LEFT`), `department`, `jobRole`, `page`.
  - **`EmployeeRecordLink({ personId })`** goes on the directory's person page.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/hrms/EmployeesPage.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aDepartment,
  aJobRole,
  anEmployee,
  anEmployeeSummary,
  anOnboardingTemplate,
  aSkillGap,
  aSummary,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/hrms/employees', permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'], ...(permissions ? { permissions } : {}) }))
    .on('GET /hr/employees', {
      body: pageOf([
        anEmployeeSummary({ id: 'e-arun', name: 'Arun Shah', employeeNumber: 'O-1', department: null, jobRole: null }),
        anEmployeeSummary({ manager: { id: 'e-ravi', name: 'Ravi Kumar', employeeNumber: 'M-1' } }),
      ]),
    })
    .on('GET /hr/departments', { body: [aDepartment()] })
    .on('GET /hr/job-roles', { body: [aJobRole()] })
    .on('GET /hr/onboarding-templates', { body: [anOnboardingTemplate()] })
    .on('GET /hr/members', { body: [{ id: 'u-meera', name: 'Meera Iyer', email: 'meera@deccan.test' }] })
    .on('GET /parties', { body: pageOf([aSummary({ id: 'p-meera', kind: 'PERSON', name: 'Meera Iyer' })]) })
    // the employee page opens after a hire
    .on('GET /hr/employees/:id', { body: anEmployee({ id: 'e-new' }) })
    .on('GET /hr/employees/:id/skill-gap', { body: aSkillGap() })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path })
}

describe('EmployeesPage', () => {
  it('lists current employees with department, role and manager', async () => {
    setup()
    const row = (await screen.findByRole('link', { name: 'Meera Iyer' })).closest('tr') as HTMLElement
    expect(row).toHaveTextContent('E-1')
    expect(row).toHaveTextContent('Sales')
    expect(row).toHaveTextContent('Account executive')
    expect(row).toHaveTextContent('Ravi Kumar')
    expect(within(row).getByText('Active')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Meera Iyer' })).toHaveAttribute('href', '/app/hrms/employees/e-meera')
  })

  it('filters by status, department, role and search', async () => {
    const { server, user } = setup()
    await screen.findByRole('link', { name: 'Meera Iyer' })
    expect(server.callsTo('GET /hr/employees')[0].query.get('status')).toBeNull()
    await user.selectOptions(screen.getByLabelText('Show'), 'LEFT')
    await user.selectOptions(screen.getByLabelText('Department'), 'd-sales')
    await user.selectOptions(screen.getByLabelText('Job role'), 'jr-exec')
    await user.type(screen.getByLabelText('Search employees'), 'E-1')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => {
      const q = server.callsTo('GET /hr/employees').at(-1)?.query
      expect(q?.get('status')).toBe('LEFT')
      expect(q?.get('departmentId')).toBe('d-sales')
      expect(q?.get('jobRoleId')).toBe('jr-exec')
      expect(q?.get('q')).toBe('E-1')
    })
  })

  it('hires a new person with onboarding and opens their page', async () => {
    const { server, user, router } = setup()
    server.on('POST /hr/employees', { status: 201, body: anEmployee({ id: 'e-new' }) })
    await user.click(await screen.findByRole('button', { name: 'Hire employee' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByLabelText('New person'))
    await user.type(within(dialog).getByLabelText('First name'), 'Zoe')
    await user.type(within(dialog).getByLabelText('Last name'), 'Nair')
    await user.type(within(dialog).getByLabelText('Email'), 'zoe@deccan.test')
    await user.type(within(dialog).getByLabelText('Employee number'), 'N-1')
    await user.selectOptions(within(dialog).getByLabelText('Department'), 'd-sales')
    await user.selectOptions(within(dialog).getByLabelText('Job role'), 'jr-exec')
    await user.selectOptions(within(dialog).getByLabelText('Employment type'), 'CONTRACT')
    await user.type(within(dialog).getByLabelText('Start date'), '2026-11-02')
    await user.selectOptions(within(dialog).getByLabelText('Team member account'), 'u-meera')
    await user.selectOptions(within(dialog).getByLabelText('Onboarding'), 'ot-std')
    await user.click(within(dialog).getByRole('button', { name: 'Hire' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/employees')[0]?.body).toEqual({
        personId: null,
        person: { firstName: 'Zoe', lastName: 'Nair', email: 'zoe@deccan.test', phone: null },
        employeeNumber: 'N-1',
        departmentId: 'd-sales',
        jobRoleId: 'jr-exec',
        managerId: null,
        employmentType: 'CONTRACT',
        startDate: '2026-11-02',
        userId: 'u-meera',
        onboardingTemplateId: 'ot-std',
      }),
    )
    await waitFor(() => expect(router.state.location.pathname).toBe('/app/hrms/employees/e-new'))
  })

  it('hires an existing person', async () => {
    const { server, user } = setup()
    server.on('POST /hr/employees', { status: 201, body: anEmployee({ id: 'e-new' }) })
    await user.click(await screen.findByRole('button', { name: 'Hire employee' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Person'), 'p-meera')
    await user.type(within(dialog).getByLabelText('Employee number'), 'E-9')
    await user.type(within(dialog).getByLabelText('Start date'), '2026-11-02')
    await user.click(within(dialog).getByRole('button', { name: 'Hire' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/employees')[0]?.body).toMatchObject({
        personId: 'p-meera',
        person: null,
        employmentType: 'FULL_TIME',
        onboardingTemplateId: null,
      }),
    )
  })

  it('needs a person, a number and a start date', async () => {
    const { server, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'Hire employee' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Hire' }))
    expect(await within(dialog).findByText('Choose a person.')).toBeInTheDocument()
    expect(within(dialog).getByText('Enter a start date.')).toBeInTheDocument()
    expect(within(dialog).getAllByText('Required.').length).toBeGreaterThanOrEqual(1)
    expect(server.callsTo('POST /hr/employees')).toHaveLength(0)
  })

  it('shows a taken number from the server on its field', async () => {
    const { server, user } = setup()
    server.on('POST /hr/employees', {
      status: 409,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'employeeNumber', message: 'Another employee already has this number.' }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Hire employee' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Person'), 'p-meera')
    await user.type(within(dialog).getByLabelText('Employee number'), 'E-1')
    await user.type(within(dialog).getByLabelText('Start date'), '2026-11-02')
    await user.click(within(dialog).getByRole('button', { name: 'Hire' }))
    expect(await within(dialog).findByText('Another employee already has this number.')).toBeInTheDocument()
  })

  it('hides Hire employee from readers', async () => {
    setup('/app/hrms/employees', ['hr.employee.read'])
    await screen.findByRole('link', { name: 'Meera Iyer' })
    expect(screen.queryByRole('button', { name: 'Hire employee' })).not.toBeInTheDocument()
  })
})
```

`frontend/src/features/hrms/EmployeeDetailPage.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aDepartment,
  aJobRole,
  anEmployee,
  anOnboardingTemplate,
  aSkill,
  aSkillGap,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'
import type { EmployeeView } from '@/lib/api/types'

function setup(employee: EmployeeView = anEmployee(), permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'], ...(permissions ? { permissions } : {}) }))
    .on('GET /hr/employees/:id', { body: employee })
    .on('GET /hr/employees/:id/skill-gap', { body: aSkillGap() })
    .on('GET /hr/employees', { body: pageOf([]) })
    .on('GET /hr/departments', { body: [aDepartment()] })
    .on('GET /hr/job-roles', { body: [aJobRole()] })
    .on('GET /hr/skills', { body: [aSkill(), aSkill({ id: 's-excel', name: 'Excel' })] })
    .on('GET /hr/onboarding-templates', { body: [anOnboardingTemplate()] })
    .on('GET /hr/members', { body: [] })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path: `/app/hrms/employees/${employee.id}` })
}

describe('EmployeeDetailPage', () => {
  it('shows the person, the employment and their skills against the role', async () => {
    setup()
    expect(await screen.findByRole('heading', { name: 'Meera Iyer · E-1' })).toBeInTheDocument()
    const details = screen.getByRole('region', { name: 'Employment' })
    expect(details).toHaveTextContent('Sales')
    expect(details).toHaveTextContent('Account executive')
    expect(details).toHaveTextContent('Full time')
    expect(within(details).getByRole('link', { name: 'Meera Iyer' })).toHaveAttribute('href', '/app/directory/p-meera')
    const gap = screen.getByRole('region', { name: 'Skills and gaps' })
    expect(within(gap).getByText('Negotiation')).toBeInTheDocument()
    expect(await within(gap).findByText(/needs 4 · has 2/)).toBeInTheDocument()
    expect(within(gap).getByText('Advanced negotiation')).toBeInTheDocument()
  })

  it('edits the skills as a whole', async () => {
    const { server, user } = setup()
    const gap = await screen.findByRole('region', { name: 'Skills and gaps' })
    const updated = anEmployee({ skills: [{ skillId: 's-neg', name: 'Negotiation', level: 4, note: null }], version: 1 })
    server.on('PUT /hr/employees/:id/skills', { body: updated }).on('GET /hr/employees/:id', { body: updated })
    await user.click(within(gap).getByRole('button', { name: 'Edit skills' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Skill 1 level'), '4')
    await user.click(within(dialog).getByRole('button', { name: 'Add skill' }))
    await user.selectOptions(within(dialog).getByLabelText('Skill 2'), 's-excel')
    await user.selectOptions(within(dialog).getByLabelText('Skill 2 level'), '3')
    await user.type(within(dialog).getByLabelText('Skill 2 note'), 'Self-taught')
    await user.click(within(dialog).getByRole('button', { name: 'Save skills' }))
    await waitFor(() =>
      expect(server.callsTo('PUT /hr/employees/:id/skills')[0]?.body).toEqual({
        skills: [
          { skillId: 's-neg', level: 4, note: null },
          { skillId: 's-excel', level: 3, note: 'Self-taught' },
        ],
        version: 0,
      }),
    )
  })

  it('ends and reinstates employment', async () => {
    const { server, user } = setup()
    const ended = anEmployee({ endDate: '2026-12-31', endReason: 'Resigned', version: 1 })
    await screen.findByRole('heading', { name: 'Meera Iyer · E-1' })
    server.on('POST /hr/employees/:id/end', { body: ended }).on('GET /hr/employees/:id', { body: ended })
    await user.click(screen.getByRole('button', { name: 'End employment' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Last day'), '2026-12-31')
    await user.type(within(dialog).getByLabelText('Reason'), 'Resigned')
    await user.click(within(dialog).getByRole('button', { name: 'End employment' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/employees/:id/end')[0]?.body).toEqual({
        endDate: '2026-12-31',
        reason: 'Resigned',
        version: 0,
      }),
    )
    expect(await screen.findByText(/Leaving on/)).toBeInTheDocument()
    const back = anEmployee({ version: 2 })
    const reinstate = await screen.findByRole('button', { name: 'Reinstate' })
    server.on('POST /hr/employees/:id/reinstate', { body: back }).on('GET /hr/employees/:id', { body: back })
    await user.click(reinstate)
    await waitFor(() =>
      expect(server.callsTo('POST /hr/employees/:id/reinstate')[0]?.body).toEqual({ version: 1 }),
    )
  })

  it('starts onboarding from a template and then shows progress', async () => {
    const { server, user } = setup()
    const onboarding = await screen.findByRole('region', { name: 'Onboarding' })
    const started = anEmployee({
      onboarding: { templateId: 'ot-std', templateName: 'Standard onboarding', done: 0, total: 2 },
      version: 1,
    })
    server.on('POST /hr/employees/:id/onboarding', { body: started }).on('GET /hr/employees/:id', { body: started })
    await user.selectOptions(within(onboarding).getByLabelText('Template'), 'ot-std')
    await user.click(within(onboarding).getByRole('button', { name: 'Start onboarding' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/employees/:id/onboarding')[0]?.body).toEqual({ templateId: 'ot-std' }),
    )
    expect(await within(onboarding).findByText('0 of 2 tasks done')).toBeInTheDocument()
  })

  it('shows readers no actions', async () => {
    setup(anEmployee(), ['hr.employee.read'])
    await screen.findByRole('heading', { name: 'Meera Iyer · E-1' })
    expect(screen.queryByRole('button', { name: 'End employment' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit skills' })).not.toBeInTheDocument()
  })
})
```

`frontend/src/features/hrms/EmployeeRecordLink.test.tsx`:

```tsx
import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { anEmployeeSummary, aPerson, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(modules: string[], employees = [anEmployeeSummary()]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules }))
    .on('GET /parties/:id', { body: aPerson({ id: 'p-meera', name: 'Meera Iyer' }) })
    .on('GET /parties', { body: pageOf([]) })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /hr/employees', { body: pageOf(employees) })
  return renderApp({ server, path: '/app/directory/p-meera' })
}

describe('EmployeeRecordLink', () => {
  it("links a person's directory page to their employee record", async () => {
    const { server } = setup(['HRMS'])
    expect(await screen.findByRole('link', { name: 'View employee record' })).toHaveAttribute(
      'href',
      '/app/hrms/employees/e-meera',
    )
    expect(server.callsTo('GET /hr/employees')[0].query.get('personId')).toBe('p-meera')
  })

  it('is absent without HRMS or without a record', async () => {
    setup([])
    await screen.findByRole('heading', { name: 'Meera Iyer' })
    expect(screen.queryByRole('link', { name: 'View employee record' })).not.toBeInTheDocument()
  })
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npx vitest run src/features/hrms`
Expected: FAIL — no employee routes.

- [ ] **Step 3: Pickers**

`frontend/src/features/hrms/MemberSelect.tsx` mirrors `features/helpdesk/AgentSelect.tsx`:
- it reads `GET /hr/members?q=` (key `['hr', 'members', q]`);
- the blank option is `No account`;
- the search label is `Find a team member`.

`frontend/src/features/hrms/EmployeePicker.tsx` mirrors `features/records/PartyPicker.tsx`:
- it searches `GET /hr/employees?q=&size=20` (key `['hr', 'employee-picker', q]`), mapping each summary to `{ id, name, employeeNumber }`;
- options read `"<name> · <number>"`, and the `current` and `picked` entries are pinned;
- the search label is `` `Find ${label.toLowerCase()}` ``, and the blank option defaults to `None`.

- [ ] **Step 4: Hiring and editing**

`frontend/src/features/hrms/EmployeeFormDialog.tsx`:

```tsx
import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextField } from '@/components/form/TextField'
import { requiredText } from '@/features/auth/schemas'
import { PartyPicker } from '@/features/records/PartyPicker'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type {
  DepartmentView,
  EmployeeView,
  EmploymentType,
  JobRoleView,
  OnboardingTemplateView,
} from '@/lib/api/types'
import { EmployeePicker } from './EmployeePicker'
import { invalidateHr } from './invalidation'
import { EMPLOYMENT_TYPE_LABELS } from './labels'
import { MemberSelect } from './MemberSelect'

export const employeeKey = (id: string) => ['hr', 'employee', id] as const

const schema = z
  .object({
    mode: z.enum(['existing', 'new']),
    personId: z.string(),
    firstName: z.string().trim().max(80, 'Use at most 80 characters.'),
    lastName: z.string().trim().max(80, 'Use at most 80 characters.'),
    email: z.string().trim().max(254, 'Use at most 254 characters.'),
    phone: z.string().trim().max(40, 'Use at most 40 characters.'),
    employeeNumber: requiredText(40),
    departmentId: z.string(),
    jobRoleId: z.string(),
    managerId: z.string(),
    employmentType: z.enum(['FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERN']),
    startDate: z.string().min(1, 'Enter a start date.'),
    userId: z.string(),
    onboardingTemplateId: z.string(),
  })
  .superRefine((v, ctx) => {
    if (v.mode === 'existing' && !v.personId)
      ctx.addIssue({ code: 'custom', path: ['personId'], message: 'Choose a person.' })
    if (v.mode === 'new' && !v.firstName)
      ctx.addIssue({ code: 'custom', path: ['firstName'], message: 'Required.' })
    if (v.mode === 'new' && !v.lastName)
      ctx.addIssue({ code: 'custom', path: ['lastName'], message: 'Required.' })
  })
type Values = z.infer<typeof schema>
const FIELDS = [
  'personId',
  'firstName',
  'lastName',
  'email',
  'phone',
  'employeeNumber',
  'departmentId',
  'jobRoleId',
  'managerId',
  'employmentType',
  'startDate',
  'userId',
] as const

/** Hire someone (D2) — an existing directory person or a new one — or edit an employment record. */
export function EmployeeFormDialog({
  employee,
  onClose,
  onSaved,
}: {
  employee?: EmployeeView
  onClose: () => void
  onSaved: (saved: EmployeeView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const departments = useQuery({
    queryKey: ['hr', 'departments', false],
    queryFn: () => api.get<DepartmentView[]>('/hr/departments'),
  })
  const jobRoles = useQuery({
    queryKey: ['hr', 'job-roles', false],
    queryFn: () => api.get<JobRoleView[]>('/hr/job-roles'),
  })
  const templates = useQuery({
    queryKey: ['hr', 'onboarding-templates', false],
    queryFn: () => api.get<OnboardingTemplateView[]>('/hr/onboarding-templates'),
    enabled: !employee,
  })
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      mode: 'existing',
      personId: employee?.person.id ?? '',
      firstName: '',
      lastName: '',
      email: '',
      phone: '',
      employeeNumber: employee?.employeeNumber ?? '',
      departmentId: employee?.department?.id ?? '',
      jobRoleId: employee?.jobRole?.id ?? '',
      managerId: employee?.manager?.id ?? '',
      employmentType: employee?.employmentType ?? 'FULL_TIME',
      startDate: employee?.startDate ?? '',
      userId: employee?.user?.id ?? '',
      onboardingTemplateId: '',
    },
  })
  const errors = form.formState.errors
  const revalidate = { shouldValidate: form.formState.isSubmitted }
  const mode = form.watch('mode')

  const onSubmit = form.handleSubmit(async (v) => {
    setFormError(null)
    const shared = {
      employeeNumber: v.employeeNumber.trim(),
      departmentId: v.departmentId || null,
      jobRoleId: v.jobRoleId || null,
      managerId: v.managerId || null,
      employmentType: v.employmentType,
      startDate: v.startDate,
      userId: v.userId || null,
    }
    const body = employee
      ? { ...shared, version: employee.version }
      : {
          personId: v.mode === 'existing' ? v.personId : null,
          person:
            v.mode === 'new'
              ? {
                  firstName: v.firstName.trim(),
                  lastName: v.lastName.trim(),
                  email: v.email.trim() || null,
                  phone: v.phone.trim() || null,
                }
              : null,
          ...shared,
          onboardingTemplateId: v.onboardingTemplateId || null,
        }
    try {
      const saved = employee
        ? await api.put<EmployeeView>(`/hr/employees/${employee.id}`, body)
        : await api.post<EmployeeView>('/hr/employees', body)
      queryClient.setQueryData(employeeKey(saved.id), saved)
      await invalidateHr(queryClient)
      toast.success(employee ? 'Changes saved.' : `${saved.person.name} hired.`)
      onSaved(saved)
    } catch (error) {
      if (employee && error instanceof ApiError && error.status === 409)
        void queryClient.invalidateQueries({ queryKey: employeeKey(employee.id) })
      // the directory reports a new person's fields without the "person." prefix
      const prefixed =
        error instanceof ApiError
          ? error.problem.errors?.find((e) => e.field.startsWith('person.'))
          : undefined
      if (prefixed)
        form.setError(prefixed.field.slice('person.'.length) as 'firstName' | 'lastName', {
          type: 'server',
          message: prefixed.message,
        })
      if (!applyFieldErrors(error, form.setError, FIELDS) && !prefixed)
        setFormError(problemMessage(error))
    }
  })

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{employee ? `Edit ${employee.person.name}` : 'Hire employee'}</DialogTitle>
          <DialogDescription>
            {employee
              ? 'Employment details. Contact details live on the person in the directory.'
              : 'Choose someone already in the directory, or add a new person.'}
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          {!employee && (
            <>
              <fieldset className="flex gap-4 text-sm">
                <legend className="sr-only">Who</legend>
                <label className="flex items-center gap-2">
                  <input type="radio" value="existing" {...form.register('mode')} /> Existing person
                </label>
                <label className="flex items-center gap-2">
                  <input type="radio" value="new" {...form.register('mode')} /> New person
                </label>
              </fieldset>
              {mode === 'existing' ? (
                <PartyPicker
                  id="field-personId"
                  label="Person"
                  kind="PERSON"
                  value={form.watch('personId')}
                  error={errors.personId?.message}
                  noneLabel="Choose…"
                  onChange={(id) => form.setValue('personId', id, revalidate)}
                />
              ) : (
                <div className="grid gap-3 sm:grid-cols-2">
                  <TextField form={form} name="firstName" label="First name" maxLength={80} />
                  <TextField form={form} name="lastName" label="Last name" maxLength={80} />
                  <TextField form={form} name="email" label="Email" type="email" />
                  <TextField form={form} name="phone" label="Phone" maxLength={40} />
                </div>
              )}
            </>
          )}
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField form={form} name="employeeNumber" label="Employee number" maxLength={40} />
            <Field id="field-startDate" label="Start date" error={errors.startDate?.message}>
              <Input
                id="field-startDate"
                type="date"
                aria-invalid={errors.startDate ? true : undefined}
                aria-describedby={describedBy('field-startDate', errors.startDate?.message)}
                {...form.register('startDate')}
              />
            </Field>
            <Field id="field-departmentId" label="Department" error={errors.departmentId?.message}>
              <NativeSelect id="field-departmentId" {...form.register('departmentId')}>
                <option value="">No department</option>
                {(departments.data ?? []).map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.name}
                  </option>
                ))}
              </NativeSelect>
            </Field>
            <Field id="field-jobRoleId" label="Job role" error={errors.jobRoleId?.message}>
              <NativeSelect id="field-jobRoleId" {...form.register('jobRoleId')}>
                <option value="">No job role</option>
                {(jobRoles.data ?? []).map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.title}
                  </option>
                ))}
              </NativeSelect>
            </Field>
            <Field id="field-employmentType" label="Employment type">
              <NativeSelect id="field-employmentType" {...form.register('employmentType')}>
                {(Object.keys(EMPLOYMENT_TYPE_LABELS) as EmploymentType[]).map((t) => (
                  <option key={t} value={t}>
                    {EMPLOYMENT_TYPE_LABELS[t]}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          </div>
          <EmployeePicker
            id="field-managerId"
            label="Manager"
            value={form.watch('managerId')}
            current={employee?.manager}
            error={errors.managerId?.message}
            onChange={(id) => form.setValue('managerId', id, revalidate)}
          />
          <MemberSelect
            id="field-userId"
            label="Team member account"
            value={form.watch('userId')}
            current={employee?.user}
            error={errors.userId?.message}
            onChange={(id) => form.setValue('userId', id, revalidate)}
          />
          {!employee && (
            <Field id="field-onboardingTemplateId" label="Onboarding">
              <NativeSelect id="field-onboardingTemplateId" {...form.register('onboardingTemplateId')}>
                <option value="">Start later</option>
                {(templates.data ?? []).map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          )}
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {employee ? 'Save changes' : 'Hire'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
```

`PartyPicker` accepts `kind="PERSON"`: it already takes `kind?: PartyKind` and passes it to `GET /parties`.

- [ ] **Step 5: The list**

`frontend/src/features/hrms/EmployeesPage.tsx` mirrors the committed `features/helpdesk/TicketsPage.tsx`: URL filters, `key={q}` on the search input, `keepPreviousData`, `refetchOnMount: 'always'`, pagination, and empty/error/loading states. Its specifics:
- **Query:** `['hr', 'employees', { q, status, department, jobRole, page }]` → `GET /hr/employees?q&status&departmentId&jobRoleId&page&size=20`.
- **Filters:**
  - `Search employees`, with placeholder "Name or number" and a `Search` button;
  - `Show`: `''` Current employees | `ACTIVE` Active | `UPCOMING` Starting soon | `LEFT` Left;
  - `Department` (`Any department` + `GET /hr/departments`);
  - `Job role` (`Any job role` + `GET /hr/job-roles`).
- **Columns:**
  - Name (link to `/app/hrms/employees/{id}`);
  - Number;
  - Department and Job role (each `—` when none);
  - Manager (`—`);
  - Status (`EMPLOYMENT_STATUS_LABELS`, as a `Badge`, `outline` for LEFT);
  - Start date (`formatDate`).
- **Header:** title `Employees`, description "Everyone who works here, and their place in the team."
- **Action:** a `Hire employee` button when `can(PERMISSIONS.hrEmployeeUpdate)`. It opens `EmployeeFormDialog`, and `onSaved` navigates to the new employee page.
- **Empty state:** with filters set, "No employees match these filters."; otherwise "No employees yet."

`routes.tsx` gains `{ path: 'employees', element: <RequirePermission anyOf={[PERMISSIONS.hrEmployeeRead]}><EmployeesPage /></RequirePermission> }`.

- [ ] **Step 6: The employee page**

`frontend/src/features/hrms/EndEmploymentDialog.tsx`:
- **Fields:** `Last day` (date input, required: "Enter the last day.") and `Reason` (`Textarea`, ≤500).
- **Description:** "Leave and training after the last day are cancelled. Their tasks, documents and history stay."
- **Submit:** button `End employment` → `POST /hr/employees/{id}/end { endDate, reason: '' → null, version }`. On success it stores the result under `employeeKey`, calls `invalidateHr` and closes. On error it shows `problemMessage` and invalidates the employee.

`frontend/src/features/hrms/SkillsDialog.tsx`:
- **Rows:** start from `employee.skills`. Each row `N` has:
  - `Skill N`, a `NativeSelect` over `GET /hr/skills` that keeps the current skill listed even if archived;
  - `Skill N level` (`SkillLevelSelect`);
  - `Skill N note` (`Input`, ≤200);
  - a `Remove skill N` button.

  An `Add skill` button appends an empty row.
- **Validation:** client-side, a skill and a level per row, and each skill once.
- **Server errors:** `skills[i].x` maps to the row's field.
- **Submit:** button `Save skills` → `PUT /hr/employees/{id}/skills { skills: [{ skillId, level: Number, note: '' → null }], version }`. On success it stores the result, calls `invalidateHr` (which also refreshes `['hr','skill-gap',id]`) and closes.

`frontend/src/features/hrms/SkillGapCard.tsx` (`<Card role="region" aria-label="Skills and gaps">`):
- **Held skills:** a list of `employee.skills` (`name` and `SKILL_LEVEL_LABELS[level]`). An `Edit skills` button opens `SkillsDialog` when `can(hrEmployeeUpdate)`.
- **The gap:** `GET /hr/employees/{id}/skill-gap` (key `['hr','skill-gap',id]`).
  - Without a job role: "No job role, so no required skills."
  - With no gaps: "Meets every skill <job role> needs."
  - Otherwise, for each gap: the skill name, a line `needs <required> · has <held ?? 'none'>`, and the recommended courses by title, each with an `Assign <course title>` button when `can(hrTraining)`. The button posts `POST /hr/training-assignments { courseId, employeeId }`, toasts "Training assigned." and calls `invalidateHr`.

`frontend/src/features/hrms/OnboardingCard.tsx` (`<Card role="region" aria-label="Onboarding">`):
- **With `employee.onboarding`:** shows the template name, a progress bar (`<progress value={done} max={total}>`) and the text `"<done> of <total> tasks done"`, plus a link `See the tasks` that scrolls to the Tasks panel (`href="#tasks"`).
- **Without it, and with `can(hrEmployeeUpdate)`:** a `Template` select (`GET /hr/onboarding-templates`, `Choose…`) and a `Start onboarding` button → `POST /hr/employees/{id}/onboarding { templateId }`, which stores the result and calls `invalidateHr`.
- **Otherwise:** "No onboarding started."

`frontend/src/features/hrms/EmployeeDetailPage.tsx`:
- **Structure:** a thin wrapper reads `employeeId` and renders `<EmployeeDetail key={employeeId} employeeId={employeeId} />`.
- **Data:** `EmployeeDetail` queries `employeeKey(id)` → `GET /hr/employees/{id}`.
- **Navigation:** a back link `← Employees` to `/app/hrms/employees`.
- **`PageHeader`:**
  - title `"<person.name> · <employeeNumber>"`;
  - description `"<jobRole?.title ?? 'No job role'> · <department?.name ?? 'No department'>"`;
  - actions: a status `Badge`, `Edit` (`EmployeeFormDialog` with `employee`), `End employment` (when `endDate` is null; opens `EndEmploymentDialog`) or `Reinstate` (when `endDate` is set; posts `/reinstate {version}` directly). All need `hrEmployeeUpdate`.
- **Notice:** when `endDate` is set and `status !== 'LEFT'`, a line `Leaving on <formatDate(endDate)>`; when `LEFT`, `Left on <formatDate(endDate)>` plus the reason.
- **Grid of cards:**
  - **`Employment`** (`role="region" aria-label="Employment"`), a `dl` with:
    - Person: link to `/app/directory/{person.id}`, then email and phone;
    - Number;
    - Department (`—`) and Job role (`—`);
    - Manager: link to `/app/hrms/employees/{manager.id}` or `—`;
    - Type (`EMPLOYMENT_TYPE_LABELS`);
    - Started (`formatDate`);
    - Account (`user.name` or `Not linked`).
  - `SkillGapCard`.
  - `OnboardingCard`.
  - `{/* leave */}` and `{/* training */}` placeholders: two JSX comments in the grid where Tasks 10 and 11 insert their cards.
- **Below the grid:**
  - `<div id="tasks"><SubjectTasksPanel subjectType="EMPLOYEE" subjectId={id} label={title} archived={status === 'LEFT'} /></div>`;
  - `ActivityPanel` and `DocumentsPanel` for `EMPLOYEE`.

`routes.tsx` gains `{ path: 'employees/:employeeId', element: <RequirePermission anyOf={[PERMISSIONS.hrEmployeeRead]}><EmployeeDetailPage /></RequirePermission> }`.

- [ ] **Step 7: The directory link**

`frontend/src/features/hrms/EmployeeRecordLink.tsx`:

```tsx
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useTenantSession } from '@/features/auth/tenantSession'
import { useApi } from '@/lib/api/ApiContext'
import type { EmployeeSummary, Page } from '@/lib/api/types'

/** On a person's directory page: a link to their employee record, when HRMS is on and they have one. */
export function EmployeeRecordLink({ personId }: { personId: string }) {
  const session = useTenantSession()
  const can = useCan()
  const api = useApi()
  const enabled =
    session.state.status === 'authenticated' &&
    session.state.profile.modules.includes('HRMS') &&
    can(PERMISSIONS.hrEmployeeRead)
  const record = useQuery({
    queryKey: ['hr', 'employees', { personId }],
    queryFn: () => api.get<Page<EmployeeSummary>>(`/hr/employees?personId=${personId}&size=1`),
    enabled,
  })
  const employee = enabled ? record.data?.items[0] : undefined
  if (!employee) return null
  return (
    <p className="text-sm">
      <Link to={`/app/hrms/employees/${employee.id}`} className="underline-offset-4 hover:underline">
        View employee record
      </Link>
    </p>
  )
}
```

In `features/directory/PartyDetailPage.tsx`, render `{p.kind === 'PERSON' && <EmployeeRecordLink personId={p.id} />}` right after the `PageHeader`.

- [ ] **Step 8: Run the tests, then the frontend checks**

Run: `cd frontend && npx vitest run src/features/hrms src/features/directory`
Expected: PASS.

Run: `cd frontend && npx prettier --write src && npm run format:check && npm run lint && npm run typecheck && npm test`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
git add frontend/src
git commit -m "feat(hrms-ui): employees — list, hiring, the employee page with skills, gaps and onboarding"
```

---

### Task 10: Leave — approvals, all requests, who's away, requesting, and balances on the employee page

**Files:**
- Create: `frontend/src/features/hrms/LeaveRequestDialog.tsx`, `DecisionDialog.tsx`, `LeavePage.tsx`, `LeavePage.test.tsx`, `LeaveCard.tsx`, `LeaveCard.test.tsx`, `AdjustBalanceDialog.tsx`
- Modify: `frontend/src/features/hrms/routes.tsx`, `EmployeeDetailPage.tsx` (insert `LeaveCard` at `{/* leave */}`)

**Interfaces:**
- Consumes (Tasks 8–9): `LeaveRequestView`, `LeaveBalanceView`, `AwayView`, `LeaveTypeView`, `LEAVE_STATUS_LABELS`, `formatDays`, `EmployeePicker`, `invalidateHr`, `employeeKey`.
- Produces:
  - `LeaveRequestDialog({ employee?, self?, onClose, onSaved })`:
    - with `self` it posts to `/hr/me/leave-requests` and has no employee field (Task 11's My HR);
    - otherwise it posts to `/hr/leave-requests` with `employeeId` (from the `employee` prop, or an `EmployeePicker` when none).
  - `LeavePage` at `/app/hrms/leave`.
  - `LeaveCard({ employee })`.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/hrms/LeavePage.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aLeaveRequest,
  anAway,
  anEmployeeSummary,
  defaultLeaveTypes,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'] }))
    .on('GET /hr/leave-requests', (r) => ({
      body:
        r.query.get('status') === 'PENDING'
          ? pageOf([aLeaveRequest()])
          : pageOf([
              aLeaveRequest(),
              aLeaveRequest({ id: 'lr-2', status: 'APPROVED', startDate: '2027-02-01', endDate: '2027-02-01', days: 1 }),
            ]),
    }))
    .on('GET /hr/leave/away', { body: [anAway()] })
    .on('GET /hr/leave-types', { body: defaultLeaveTypes() })
    .on('GET /hr/employees', { body: pageOf([anEmployeeSummary()]) })
  return renderApp({ server, path: '/app/hrms/leave' })
}

describe('LeavePage', () => {
  it('shows requests waiting for a decision, all requests and who is away', async () => {
    setup()
    const queue = await screen.findByRole('region', { name: 'Waiting for a decision' })
    expect(await within(queue).findByText('Meera Iyer')).toBeInTheDocument()
    expect(queue).toHaveTextContent('2 days')
    const all = screen.getByRole('region', { name: 'All requests' })
    // the Status filter also lists "Approved": look in the table only
    const table = await within(all).findByRole('table')
    expect(await within(table).findByText('Approved')).toBeInTheDocument()
    const away = screen.getByRole('region', { name: "Who's away" })
    expect(await within(away).findByRole('link', { name: 'Meera Iyer' })).toBeInTheDocument()
  })

  it('approves with a note', async () => {
    const { server, user } = setup()
    const queue = await screen.findByRole('region', { name: 'Waiting for a decision' })
    const approve = await within(queue).findByRole('button', { name: 'Approve Meera Iyer 2027-03-01' })
    server.on('POST /hr/leave-requests/:id/approve', { body: aLeaveRequest({ status: 'APPROVED', version: 1 }) })
    server.on('GET /hr/leave-requests', { body: pageOf([]) })
    await user.click(approve)
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Note'), 'Enjoy')
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/leave-requests/:id/approve')[0]?.body).toEqual({ note: 'Enjoy', version: 0 }),
    )
    expect(await within(queue).findByText('Nothing is waiting for a decision.')).toBeInTheDocument()
  })

  it('shows why a decision was refused', async () => {
    const { server, user } = setup()
    const queue = await screen.findByRole('region', { name: 'Waiting for a decision' })
    const reject = await within(queue).findByRole('button', { name: 'Reject Meera Iyer 2027-03-01' })
    server.on('POST /hr/leave-requests/:id/reject', {
      status: 409,
      body: { detail: 'Someone else must decide your own request.' },
    })
    await user.click(reject)
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }))
    expect(await within(dialog).findByText('Someone else must decide your own request.')).toBeInTheDocument()
  })

  it('records leave for someone', async () => {
    const { server, user } = setup()
    server.on('POST /hr/leave-requests', { status: 201, body: aLeaveRequest({ id: 'lr-new' }) })
    await user.click(await screen.findByRole('button', { name: 'Request leave for someone' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Employee'), 'e-meera')
    await user.selectOptions(within(dialog).getByLabelText('Leave type'), 'lt-annual')
    await user.type(within(dialog).getByLabelText('From'), '2027-03-01')
    await user.type(within(dialog).getByLabelText('To'), '2027-03-02')
    await user.type(within(dialog).getByLabelText('Reason'), 'Family')
    await user.click(within(dialog).getByRole('button', { name: 'Request leave' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/leave-requests')[0]?.body).toEqual({
        employeeId: 'e-meera',
        typeId: 'lt-annual',
        startDate: '2027-03-01',
        endDate: '2027-03-02',
        reason: 'Family',
      }),
    )
  })

  it('shows balance and overlap refusals in the dialog', async () => {
    const { server, user } = setup()
    server.on('POST /hr/leave-requests', {
      status: 409,
      body: { detail: 'Not enough leave left: 2.5 days available.' },
    })
    await user.click(await screen.findByRole('button', { name: 'Request leave for someone' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Employee'), 'e-meera')
    await user.selectOptions(within(dialog).getByLabelText('Leave type'), 'lt-annual')
    await user.type(within(dialog).getByLabelText('From'), '2027-03-01')
    await user.type(within(dialog).getByLabelText('To'), '2027-03-05')
    await user.click(within(dialog).getByRole('button', { name: 'Request leave' }))
    expect(await within(dialog).findByText('Not enough leave left: 2.5 days available.')).toBeInTheDocument()
  })

  it('refuses an end before the start before calling the server', async () => {
    const { server, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'Request leave for someone' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Employee'), 'e-meera')
    await user.selectOptions(within(dialog).getByLabelText('Leave type'), 'lt-annual')
    await user.type(within(dialog).getByLabelText('From'), '2027-03-05')
    await user.type(within(dialog).getByLabelText('To'), '2027-03-01')
    await user.click(within(dialog).getByRole('button', { name: 'Request leave' }))
    expect(await within(dialog).findByText("The end date can't be before the start date.")).toBeInTheDocument()
    expect(server.callsTo('POST /hr/leave-requests')).toHaveLength(0)
  })
})
```

`frontend/src/features/hrms/LeaveCard.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aLeaveBalance,
  aLeaveRequest,
  anEmployee,
  anOnboardingTemplate,
  aSkillGap,
  defaultLeaveTypes,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'] }))
    .on('GET /hr/employees/:id', { body: anEmployee() })
    .on('GET /hr/employees/:id/skill-gap', { body: aSkillGap() })
    .on('GET /hr/employees/:id/leave-balances', {
      body: [aLeaveBalance(), aLeaveBalance({ type: { id: 'lt-unpaid', name: 'Unpaid leave' }, paid: false,
        entitlement: null, available: null, taken: 1, pending: 0 })],
    })
    .on('GET /hr/leave-requests', { body: pageOf([aLeaveRequest()]) })
    .on('GET /hr/leave-types', { body: defaultLeaveTypes() })
    .on('GET /hr/onboarding-templates', { body: [anOnboardingTemplate()] })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path: '/app/hrms/employees/e-meera' })
}

describe('LeaveCard', () => {
  it("shows the year's balances and requests", async () => {
    const { server } = setup()
    const card = await screen.findByRole('region', { name: 'Leave' })
    const annual = (await within(card).findByText('Annual leave')).closest('tr') as HTMLElement
    expect(annual).toHaveTextContent('20 days')
    expect(annual).toHaveTextContent('14 days')
    const unpaid = within(card).getByText('Unpaid leave').closest('tr') as HTMLElement
    expect(unpaid).toHaveTextContent('Unlimited')
    // "Pending" is also a balance column: look in the request list only
    const requests = await within(card).findByRole('list', { name: 'Leave requests' })
    expect(within(requests).getByText('Pending')).toBeInTheDocument()
    expect(server.callsTo('GET /hr/leave-requests')[0].query.get('employeeId')).toBe('e-meera')
  })

  it('adjusts a balance with a reason', async () => {
    const { server, user } = setup()
    const card = await screen.findByRole('region', { name: 'Leave' })
    const adjust = await within(card).findByRole('button', { name: 'Adjust balance' })
    server.on('POST /hr/employees/:id/leave-adjustments', { body: [aLeaveBalance({ entitlement: 22.5 })] })
    await user.click(adjust)
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Leave type'), 'lt-annual')
    await user.type(within(dialog).getByLabelText('Days (use a minus sign to take days away)'), '2.5')
    await user.type(within(dialog).getByLabelText('Reason'), 'Carry-over agreed')
    await user.click(within(dialog).getByRole('button', { name: 'Adjust' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/employees/:id/leave-adjustments')[0]?.body).toMatchObject({
        typeId: 'lt-annual',
        days: 2.5,
        reason: 'Carry-over agreed',
      }),
    )
  })
})
```

The year sent with an adjustment is the year shown on the card (`Year` select, default current year). The test checks the other fields with `toMatchObject`.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npx vitest run src/features/hrms/LeavePage.test.tsx src/features/hrms/LeaveCard.test.tsx`
Expected: FAIL.

- [ ] **Step 3: Requesting and deciding**

`frontend/src/features/hrms/LeaveRequestDialog.tsx`:
- **Fields** (RHF + Zod):
  - `Employee`: an `EmployeePicker`, shown only when neither `employee` nor `self` is given; required `"Choose an employee."`;
  - `Leave type`: a `NativeSelect` over `GET /hr/leave-types` (key `['hr','leave-types',false]`), `Choose…`; required `"Choose a leave type."`. Each option reads `"<name>"` plus ` (unpaid)` when `!paid`;
  - `From` and `To`: `type="date"`, required `"Enter the first day."` / `"Enter the last day."`;
  - `Reason`: `Textarea`, ≤500.
- **Validation:** a refine on `To` — `"The end date can't be before the start date."` when `to < from`.
- **Body:** `{ employeeId?, typeId, startDate, endDate, reason: '' → null }`. Omit `employeeId` with `self`.
- **Submit:**
  - posts to `self ? '/hr/me/leave-requests' : '/hr/leave-requests'`;
  - on success: `invalidateHr`, toast "Leave requested: <days> working day(s).", `onSaved`;
  - field errors map (`startDate` → From, `endDate` → To, `typeId`, `employeeId`); other problems (409s) show in a `FormError`.
- **Labels:** submit button `Request leave`; dialog title `Request leave`; description "Working days are counted for you: weekends and public holidays don't count."

`frontend/src/features/hrms/DecisionDialog.tsx` (`{ request, outcome: 'approve' | 'reject', onClose }`):
- **Title:** `"Approve leave for <employee.name>"` or `"Reject leave for <employee.name>"`.
- **Summary:** dates (`formatDate`), `formatDays(days)`, type and reason.
- **Field:** `Note` (`Textarea`, ≤500, optional).
- **Submit:** button `Approve` or `Reject` → `POST /hr/leave-requests/{id}/{outcome} { note: '' → null, version }`. On success: `invalidateHr`, toast, close. On failure: `problemMessage` in a `FormError` inside the dialog, then `invalidateHr` (the version may be stale).

- [ ] **Step 4: The leave tab**

`frontend/src/features/hrms/LeavePage.tsx`:
- **Header:** title `Leave`, description "Approve requests, see everyone's leave and who is away." Action: a `Request leave for someone` button that opens `LeaveRequestDialog` with no employee.
- **Section `Waiting for a decision`** (`<section aria-label="Waiting for a decision">`):
  - query `['hr','leave-requests',{ status: 'PENDING' }]` → `GET /hr/leave-requests?status=PENDING&size=50`;
  - a table of Employee (link), Type, Dates (`formatDate(start)` – `formatDate(end)`), Days (`formatDays`), Reason (`—`), Requested (`formatDateTime`);
  - two buttons per row with accessible names `Approve <employee name> <startDate>` and `Reject <employee name> <startDate>` (visible text `Approve`/`Reject`, the name in `aria-label`), each opening `DecisionDialog`;
  - empty state: "Nothing is waiting for a decision."
- **Section `All requests`:**
  - filters `Status` (`Any status` + `LEAVE_STATUS_LABELS`) and `Employee` (`EmployeePicker` with id `leave-filter-employee`, blank `Anyone`; the dialog's picker uses `field-employeeId`, so the two never share an id);
  - query `['hr','leave-requests',{ status, employeeId, page }]` → `GET /hr/leave-requests?status&employeeId&page&size=20`;
  - a table of Employee, Type, Dates, Days and Status (`Badge`), plus `Cancel <employee name> <startDate>` for PENDING or APPROVED requests whose `endDate >= today` (`todayIso()`). It opens a `ConfirmDialog` ("Cancel this leave?", confirm `Cancel leave`) → `POST /hr/leave-requests/{id}/cancel { version }`;
  - pagination.
- **Section `Who's away`:**
  - `From` and `To` date inputs (default today and today + 13 days, `todayIso()` plus date arithmetic in local time);
  - query `['hr','away',from,to]` → `GET /hr/leave/away?from&to`;
  - a list of the employee (link to `/app/hrms/employees/{id}`), type and dates;
  - empty state: "Nobody is away in these dates."

`routes.tsx` gains `{ path: 'leave', element: <RequirePermission anyOf={[PERMISSIONS.hrLeaveApprove]}><LeavePage /></RequirePermission> }`.

- [ ] **Step 5: Balances and requests on the employee page**

`frontend/src/features/hrms/AdjustBalanceDialog.tsx` (`{ employee, year, onClose }`):
- **Fields:**
  - `Leave type`: types with an allowance only (`annualAllowance !== null`);
  - `Days (use a minus sign to take days away)`: `inputMode="decimal"`, a non-zero multiple of 0.5 between −366 and 366, else `"Use a non-zero number of days in steps of 0.5."`;
  - `Reason`: required, ≤200.
- **Submit:** button `Adjust` → `POST /hr/employees/{id}/leave-adjustments { typeId, year, days: Number, reason }`, then `invalidateHr`, toast "Balance adjusted.", close.

`frontend/src/features/hrms/LeaveCard.tsx` (`<Card role="region" aria-label="Leave">`):
- **Year select:** label `Year`; the current year, the next and the previous; default current.
- **Balances table:** `GET /hr/employees/{id}/leave-balances?year=` (key `['hr','balances',id,year]`).
  - Columns: Type, Entitlement, Taken, Pending, Available, each through `formatDays`.
  - An `Adjust balance` button when `can(hrEmployeeUpdate)`.
- **Requests for the person:** shown when `can(hrLeaveApprove)`. `GET /hr/leave-requests?employeeId=<id>&size=10` (key `['hr','leave-requests',{ employeeId: id }]`) renders a `<ul aria-label="Leave requests">` of dates, type, days and status.
  - A `Request leave` button opens `LeaveRequestDialog` with the employee.
  - Without `hrLeaveApprove`, the list is omitted (the endpoint is approver-only).

In `EmployeeDetailPage.tsx`, replace `{/* leave */}` with `<LeaveCard employee={e} />`.

- [ ] **Step 6: Run the tests, then the frontend checks**

Run: `cd frontend && npx vitest run src/features/hrms`
Expected: PASS.

Run: `cd frontend && npx prettier --write src && npm run format:check && npm run lint && npm run typecheck && npm test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat(hrms-ui): leave — approvals, all requests, who's away, requesting and balances"
```

---

### Task 11: Training, the policy library and My HR

**Files:**
- Create: `frontend/src/features/hrms/CourseFormDialog.tsx`, `AssignTrainingDialog.tsx`, `TrainingPage.tsx`, `TrainingPage.test.tsx`, `TrainingCard.tsx`, `PolicyFormDialog.tsx`, `PoliciesPage.tsx`, `PoliciesPage.test.tsx`, `PolicyDetailPage.tsx`, `PolicyDetailPage.test.tsx`, `MyHrPage.tsx`, `MyHrPage.test.tsx`
- Modify: `frontend/src/features/hrms/routes.tsx`, `EmployeeDetailPage.tsx` (insert `TrainingCard` at `{/* training */}`), `HrLayout.test.tsx` (self-service case)

**Interfaces:**
- Consumes (Tasks 8–10): types, fixtures, labels, `SkillLevelSelect`, `EmployeePicker`, `invalidateHr`, `LeaveRequestDialog` (`self`), `formatDays`.
- Produces: `TrainingPage` at `/app/hrms/training`, `PoliciesPage` at `/app/hrms/policies`, `PolicyDetailPage` at `/app/hrms/policies/:policyId`, `MyHrPage` at `/app/hrms/me`, and `TrainingCard({ employee })`.

- [ ] **Step 1: Write the failing tests**

`frontend/src/features/hrms/TrainingPage.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aCourse, anEmployeeSummary, aSkill, aTrainingAssignment, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/hrms/training', permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'], ...(permissions ? { permissions } : {}) }))
    .on('GET /hr/courses', { body: [aCourse()] })
    .on('GET /hr/training-assignments', {
      body: pageOf([aTrainingAssignment(), aTrainingAssignment({ id: 'ta-2', overdue: true, dueOn: '2026-09-01' })]),
    })
    .on('GET /hr/skills', { body: [aSkill()] })
    .on('GET /hr/employees', { body: pageOf([anEmployeeSummary()]) })
  return renderApp({ server, path })
}

describe('TrainingPage', () => {
  it('lists courses with the skills they teach, and assignments with overdue ones marked', async () => {
    setup()
    const courses = await screen.findByRole('region', { name: 'Courses' })
    expect(await within(courses).findByText('Advanced negotiation')).toBeInTheDocument()
    expect(courses).toHaveTextContent('Negotiation (4)')
    expect(courses).toHaveTextContent('3.5 h')
    const assignments = screen.getByRole('region', { name: 'Assignments' })
    expect(await within(assignments).findByText('Overdue')).toBeInTheDocument()
  })

  it('reads the overdue filter from the URL', async () => {
    const { server } = setup('/app/hrms/training?overdue=true')
    await screen.findByRole('region', { name: 'Assignments' })
    await waitFor(() =>
      expect(server.callsTo('GET /hr/training-assignments')[0]?.query.get('overdue')).toBe('true'),
    )
  })

  it('creates a course that teaches a skill', async () => {
    const { server, user } = setup()
    server.on('POST /hr/courses', { status: 201, body: aCourse({ id: 'c-new' }) })
    await user.click(await screen.findByRole('button', { name: 'New course' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Title'), 'Negotiation basics')
    await user.type(within(dialog).getByLabelText('Duration (hours)'), '2')
    await user.type(within(dialog).getByLabelText('Link'), 'https://learn.example/basics')
    await user.click(within(dialog).getByRole('button', { name: 'Add skill' }))
    await user.selectOptions(within(dialog).getByLabelText('Skill 1'), 's-neg')
    await user.selectOptions(within(dialog).getByLabelText('Skill 1 level'), '3')
    await user.click(within(dialog).getByRole('button', { name: 'Create course' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/courses')[0]?.body).toEqual({
        title: 'Negotiation basics',
        description: null,
        durationHours: 2,
        link: 'https://learn.example/basics',
        skills: [{ skillId: 's-neg', level: 3 }],
      }),
    )
  })

  it('assigns, completes and cancels training', async () => {
    const { server, user } = setup()
    server.on('POST /hr/training-assignments', { status: 201, body: aTrainingAssignment({ id: 'ta-new' }) })
    await user.click(await screen.findByRole('button', { name: 'Assign training' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Course'), 'c-adv')
    await user.selectOptions(within(dialog).getByLabelText('Employee'), 'e-meera')
    await user.type(within(dialog).getByLabelText('Due'), '2027-01-31')
    await user.click(within(dialog).getByRole('button', { name: 'Assign' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/training-assignments')[0]?.body).toEqual({
        courseId: 'c-adv',
        employeeId: 'e-meera',
        dueOn: '2027-01-31',
      }),
    )
    const assignments = screen.getByRole('region', { name: 'Assignments' })
    const complete = (await within(assignments).findAllByRole('button', { name: /^Mark complete/ }))[0]
    server.on('POST /hr/training-assignments/:id/complete', {
      body: aTrainingAssignment({ status: 'COMPLETED', completedOn: '2026-10-09', version: 1 }),
    })
    await user.click(complete)
    await waitFor(() =>
      expect(server.callsTo('POST /hr/training-assignments/:id/complete')[0]?.body).toEqual({
        note: null,
        version: 0,
      }),
    )
  })

  it('shows readers no training actions', async () => {
    setup('/app/hrms/training', ['hr.employee.read'])
    await screen.findByRole('region', { name: 'Courses' })
    expect(screen.queryByRole('button', { name: 'New course' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Assign training' })).not.toBeInTheDocument()
  })
})
```

`frontend/src/features/hrms/PoliciesPage.test.tsx` and `PolicyDetailPage.test.tsx` mirror the committed `features/helpdesk/ArticlesPage.test.tsx` and `ArticleDetailPage.test.tsx`. The substitutions:
- **Routes:**
  - `/app/hrms/policies` and `/app/hrms/policies/:id`;
  - fake routes `GET /hr/policies` (`pageOf([aPolicySummary(), aPolicySummary({ id: 'pol-2', title: 'Travel policy', status: 'DRAFT', publishedAt: null })])`), `GET /hr/policies/:id`, `POST /hr/policies`, `PUT /hr/policies/:id`, `POST /hr/policies/:id/publish|unpublish|archive`.
- **Fixtures:** `aPolicy`/`aPolicySummary`.
- **Labels:**
  - page heading `Policies`; buttons `New policy`, `Save draft`, `Publish`, `Unpublish`, `Archive`, confirm `Archive policy`;
  - search label `Search policies`; status filter `Status` (managers only); dialog fields `Title` and `Policy`.
- **Request body:** a create is `{ title, body }` (no category).
- **Who sees what:**
  - A staff user with only `hr.self.service` sees no `Status` filter, no `New policy` and no actions on the detail page.
  - Managers have both `hr.self.service` and `hr.policy.manage` (`testProfile()` has all permissions).

There is no category filter (policies have none). Every test of the article originals has a counterpart, including the "wait for the action button, then register the refetch GET, then click" order and the `key={q}` navigation test.

`frontend/src/features/hrms/MyHrPage.test.tsx`:

```tsx
import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aLeaveRequest, aMe, aPolicySummary, aTrainingAssignment, defaultLeaveTypes, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(me = aMe()) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'], permissions: ['hr.self.service'] }))
    .on('GET /hr/me', { body: me })
    .on('GET /hr/leave-types', { body: defaultLeaveTypes() })
    .on('GET /hr/policies', { body: pageOf([aPolicySummary()]) })
  return renderApp({ server, path: '/app/hrms/me' })
}

describe('MyHrPage', () => {
  it('shows my balances, requests, trainings and the policies', async () => {
    setup()
    expect(await screen.findByRole('heading', { name: 'My HR' })).toBeInTheDocument()
    const balances = screen.getByRole('region', { name: 'My leave' })
    expect(balances).toHaveTextContent('Annual leave')
    expect(balances).toHaveTextContent('14 days')
    const trainings = screen.getByRole('region', { name: 'My training' })
    expect(trainings).toHaveTextContent('Advanced negotiation')
    expect(screen.getByRole('link', { name: 'Leave policy' })).toHaveAttribute('href', '/app/hrms/policies/pol-1')
  })

  it('requests leave for myself', async () => {
    const { server, user } = setup()
    const request = await screen.findByRole('button', { name: 'Request leave' })
    server.on('POST /hr/me/leave-requests', { status: 201, body: aLeaveRequest({ id: 'lr-new' }) })
    await user.click(request)
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).queryByLabelText('Employee')).not.toBeInTheDocument()
    await user.selectOptions(within(dialog).getByLabelText('Leave type'), 'lt-annual')
    await user.type(within(dialog).getByLabelText('From'), '2027-03-01')
    await user.type(within(dialog).getByLabelText('To'), '2027-03-02')
    await user.click(within(dialog).getByRole('button', { name: 'Request leave' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/me/leave-requests')[0]?.body).toEqual({
        typeId: 'lt-annual',
        startDate: '2027-03-01',
        endDate: '2027-03-02',
        reason: null,
      }),
    )
  })

  it('cancels my pending request and completes my training', async () => {
    const { server, user } = setup()
    const cancel = await screen.findByRole('button', { name: 'Cancel leave 2027-03-01' })
    const updated = aMe({ requests: [aLeaveRequest({ status: 'CANCELLED', cancelledAt: '2026-10-09T10:00:00Z', version: 1 })] })
    server.on('POST /hr/me/leave-requests/:id/cancel', { body: aLeaveRequest({ status: 'CANCELLED' }) })
      .on('GET /hr/me', { body: updated })
    await user.click(cancel)
    await user.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel leave' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/me/leave-requests/:id/cancel')[0]?.body).toEqual({ version: 0 }),
    )
    const complete = await screen.findByRole('button', { name: 'Mark Advanced negotiation complete' })
    server.on('POST /hr/me/training/:id/complete', { body: aTrainingAssignment({ status: 'COMPLETED' }) })
      .on('GET /hr/me', { body: aMe({ trainings: [] }) })
    await user.click(complete)
    await waitFor(() =>
      expect(server.callsTo('POST /hr/me/training/:id/complete')[0]?.body).toEqual({ note: null, version: 0 }),
    )
  })

  it('explains an account that is not linked', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['HRMS'], permissions: ['hr.self.service'] }))
      .on('GET /hr/me', { status: 404, body: { detail: "Your account isn't linked to an employee record." } })
      .on('GET /hr/policies', { body: pageOf([]) })
    renderApp({ server, path: '/app/hrms/me' })
    expect(await screen.findByText("Your account isn't linked to an employee record.")).toBeInTheDocument()
    expect(screen.getByText('Ask HR to link your account to your employee record.')).toBeInTheDocument()
  })
})
```

Add the self-service case to `HrLayout.test.tsx` (Task 8, case 4):

```tsx
  it('sends a self-service user to My HR', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['HRMS'], permissions: ['hr.self.service'] }))
      .on('GET /hr/me', { body: aMe() })
      .on('GET /hr/policies', { body: pageOf([]) })
    const { router } = renderApp({ server, path: '/app/hrms' })
    expect(await screen.findByRole('heading', { name: 'My HR' })).toBeInTheDocument()
    expect(router.state.location.pathname).toBe('/app/hrms/me')
  })
```

`HrIndex` sends someone with `hr.self.service` but without `hr.employee.read` straight to My HR (Step 5), not to the first allowed tab.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npx vitest run src/features/hrms`
Expected: FAIL — no training, policy or My HR routes.

- [ ] **Step 3: Training**

`frontend/src/features/hrms/CourseFormDialog.tsx`:
- **Fields:**
  - `Title` (≤200, required);
  - `Description` (`Textarea`, ≤5000);
  - `Duration (hours)`: `inputMode="decimal"`, blank = none, else > 0 and ≤ 999, `"Use more than 0 and at most 999 hours."`;
  - `Link`: blank or `http(s)://`, `"Use a web address starting with http:// or https://."`;
  - a skills editor, with the same row pattern as the job-role editor in Task 8 (`Skill N`, `Skill N level`, `Remove skill N`, `Add skill`).
- **Body:** `{ title, description: '' → null, durationHours: '' → null | Number, link: '' → null, skills: [{ skillId, level: Number }] }`, plus `version` on edit.
- **Submit labels:** `Create course` / `Save changes`.

`frontend/src/features/hrms/AssignTrainingDialog.tsx` (`{ employee?, course?, onClose }`):
- **Fields:**
  - `Course`: a `NativeSelect` of active courses, hidden when `course` is given;
  - `Employee`: an `EmployeePicker`, hidden when `employee` is given;
  - `Due`: date, optional.
- **Submit:** button `Assign` → `POST /hr/training-assignments { courseId, employeeId, dueOn: '' → null }`. On success `invalidateHr` and toast "Training assigned."; a 409 shows its detail.

`frontend/src/features/hrms/TrainingPage.tsx`:
- **Header:** title `Training`, description "Courses that teach the skills your roles need, and who is taking them."
- **Actions:** `New course` and `Assign training`, both only with `can(hrTraining)`.
- **Section `Courses`:**
  - `GET /hr/courses` (key `['hr','courses',false]`);
  - a table of Title (with `link` as an external `<a target="_blank" rel="noreferrer">`), Skills taught (`"<name> (<level>)"`, comma-joined) and Duration (`"<n> h"` or `—`);
  - `Edit <title>` / `Archive <title>` with `hrTraining`.
- **Section `Assignments`:**
  - filters `Status` (`Any status` + `TRAINING_STATUS_LABELS`) and an `Overdue only` checkbox, both mirrored in the URL (`status`, `overdue=true`);
  - query `['hr','training',{ status, overdue, page }]` → `GET /hr/training-assignments?status&overdue&page&size=20`;
  - a table of Employee (link), Course, Due (`formatDate`, plus a destructive `Badge` with text `Overdue` when `overdue`) and Status;
  - for open ones with `hrTraining`, a `Mark complete <course> for <employee>` button (`POST …/{id}/complete { note: null, version }`) and `Cancel <course> for <employee>` (`POST …/{id}/cancel { version }`, through a `ConfirmDialog`).

`frontend/src/features/hrms/TrainingCard.tsx` (`<Card role="region" aria-label="Training">`):
- open assignments of the employee: `GET /hr/training-assignments?employeeId=&status=ASSIGNED` (key `['hr','training',{ employeeId, status: 'ASSIGNED' }]`), showing course, due date and an Overdue badge;
- an `Assign training` button (`AssignTrainingDialog` with the employee) when `can(hrTraining)`;
- empty state "No open training."

In `EmployeeDetailPage.tsx`, replace `{/* training */}` with `<TrainingCard employee={e} />`.

- [ ] **Step 4: Policies**

Create `PoliciesPage.tsx`, `PolicyDetailPage.tsx` and `PolicyFormDialog.tsx` by mirroring the committed `features/helpdesk/ArticlesPage.tsx`, `ArticleDetailPage.tsx` and `ArticleFormDialog.tsx`, with these substitutions:
- **Routes:** API `/hr/policies…`; query keys `['hr','policies',filters]` and `['hr','policy',id]`.
- **Permissions:** `can(PERMISSIONS.hrPolicy)` for managing.
- **Remove every category input, filter and display.**
- **Labels:**
  - page title `Policies`, description "The rules everyone works by. Published policies are visible to everyone with self-service.";
  - form fields `Title` and `Policy` (the body, rows 14, ≤50 000);
  - dialog description "Plain text. Write it so anyone can follow it.";
  - `invalidateHr` instead of `invalidateHelpDesk`;
  - archive confirmation "It can't be published again." with confirm `Archive policy`.

`PolicyDetailPage` renders its body with `key={policyId}` from a thin wrapper and has no Documents panel.

`routes.tsx` gains:
- `{ path: 'training', element: <RequirePermission anyOf={[PERMISSIONS.hrEmployeeRead]}><TrainingPage /></RequirePermission> }`;
- `policies` and `policies/:policyId`, each with `<RequirePermission anyOf={[PERMISSIONS.hrSelf, PERMISSIONS.hrPolicy]}>`;
- `me` with `<RequirePermission anyOf={[PERMISSIONS.hrSelf]}><MyHrPage /></RequirePermission>`.

- [ ] **Step 5: My HR**

`frontend/src/features/hrms/MyHrPage.tsx`:
- **Data:** query `['hr','me']` → `GET /hr/me`. On a 404, show the problem detail (`problemMessage`) and the line "Ask HR to link your account to your employee record." instead of the sections.
- **Header:** title `My HR`; description `"<person.name> · <employeeNumber> · <jobRole?.title ?? 'No job role'>"`.
- **Section `My leave`:**
  - balances for `me.year` (type, available via `formatDays`, and "of <entitlement>" when limited);
  - a `Request leave` button → `<LeaveRequestDialog self />`;
  - my requests (dates, type, days, status), each PENDING or upcoming APPROVED one (`startDate > todayIso()`) with a `Cancel leave <startDate>` button. It opens a `ConfirmDialog` (confirm `Cancel leave`) → `POST /hr/me/leave-requests/{id}/cancel { version }`, then `invalidateHr`.
- **Section `My training`:** each open training (course, due date, Overdue badge) with a `Mark <course title> complete` button → `POST /hr/me/training/{id}/complete { note: null, version }`, then `invalidateHr`. Empty state: "No training assigned."
- **Section `My onboarding`:** shown only when `me.employee.onboarding`, as `"<done> of <total> tasks done"` and a link `See my tasks` to `/app/tasks?assignee=me`.
- **Section `Policies`:** `GET /hr/policies?size=10` (published ones for staff), each a link to `/app/hrms/policies/{id}`.

`HrIndex.tsx`:

```tsx
export function HrIndex() {
  const can = useCan()
  if (can(PERMISSIONS.hrEmployeeRead)) return <HrOverviewPage />
  if (can(PERMISSIONS.hrSelf)) return <Navigate to="/app/hrms/me" replace />
  const first = TABS.find((tab) => can(...tab.anyOf))
  return first ? <Navigate to={first.to} replace /> : null
}
```

- [ ] **Step 6: Run the tests, then the frontend checks**

Run: `cd frontend && npx vitest run src/features/hrms`
Expected: PASS.

Run: `cd frontend && npx prettier --write src && npm run format:check && npm run lint && npm run typecheck && npm test`
Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add frontend/src
git commit -m "feat(hrms-ui): training, the policy library and My HR"
```

---

### Task 12: The end-to-end journey and the README

**Files:**
- Create: `frontend/e2e/hrms.spec.ts`
- Modify: `README.md`

**Interfaces:**
- Consumes:
  - every HRMS screen from Tasks 8–11;
  - the seeded "Standard onboarding" template and leave types;
  - `e2e/support/mailpit.ts` (`emailText`, `tokenFromEmail`) and `e2e/support/workspace.ts`;
  - the emails:
    - the task assignment email "You've been assigned: <task title>";
    - the leave request email "Leave request: <name>, <start> to <end>" to the manager;
    - the decision email "Your leave request was approved" to the employee;
    - the training email "Training assigned: <course>".

- [ ] **Step 1: The journey (spec §7 E2E)**

`frontend/e2e/hrms.spec.ts`:

```ts
import { expect, test, type Page } from '@playwright/test'
import { emailText, tokenFromEmail } from './support/mailpit'
import { newWorkspace, PASSWORD, signIn, signUpAndVerify } from './support/workspace'

function hrTab(page: Page, name: string) {
  return page.getByRole('navigation', { name: 'HRMS' }).getByRole('link', { name })
}

/** yyyy-mm-dd in local time. */
function iso(d: Date): string {
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** The next Tuesday and Wednesday at least a day away: two working days in one calendar year and within 7 days. */
function nextTuesdayAndWednesday(): [string, string] {
  const d = new Date()
  d.setDate(d.getDate() + 1)
  while (d.getDay() !== 2) d.setDate(d.getDate() + 1)
  const wed = new Date(d)
  wed.setDate(d.getDate() + 1)
  return [iso(d), iso(wed)]
}

test('a new hire is onboarded, closes a skill gap with training, and takes approved leave', async ({
  page,
  browser,
}) => {
  const ws = newWorkspace('hr')
  const hireEmail = `zoe-${ws.slug}@e2e.test`
  await signUpAndVerify(page, ws)
  await signIn(page, ws, ws.email)
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()
  const nav = page.getByRole('navigation', { name: 'Workspace' })

  // Enable HRMS; the new hire gets an account.
  await page.goto('/app/settings/modules')
  await page.getByRole('switch', { name: 'HRMS' }).click()
  await expect(page.getByText('HRMS enabled.')).toBeVisible()
  await page.getByRole('navigation', { name: 'Settings' }).getByRole('link', { name: 'Users' }).click()
  await page.getByRole('button', { name: 'Invite people' }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByLabel('Email').fill(hireEmail)
  await dialog.getByLabel('Role').selectOption({ label: 'TENANT_ADMIN' })
  await dialog.getByRole('button', { name: 'Send invitation' }).click()
  await expect(page.getByText(`Invitation sent to ${hireEmail}.`)).toBeVisible()
  const token = await tokenFromEmail(hireEmail, '/invite/accept')
  const hireContext = await browser.newContext()
  const hire = await hireContext.newPage()
  await hire.goto(`/invite/accept?token=${encodeURIComponent(token)}`)
  await hire.getByLabel('First name').fill('Zoe')
  await hire.getByLabel('Last name').fill('Nair')
  await hire.getByLabel('Password', { exact: true }).fill(PASSWORD)
  await hire.getByLabel('Repeat password', { exact: true }).fill(PASSWORD)
  await hire.getByRole('button', { name: `Join ${ws.name}` }).click()
  await expect(hire.getByRole('heading', { name: "You're in" })).toBeVisible()

  // A job role that needs Negotiation at level 4, and a course that teaches it.
  await page.getByRole('navigation', { name: 'Settings' }).getByRole('link', { name: 'HRMS' }).click()
  const skills = page.getByRole('region', { name: 'Skills' })
  await skills.getByRole('button', { name: 'New skill' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Name').fill('Negotiation')
  await dialog.getByRole('button', { name: 'Create skill' }).click()
  await expect(skills.getByText('Negotiation')).toBeVisible()
  const roles = page.getByRole('region', { name: 'Job roles' })
  await roles.getByRole('button', { name: 'New job role' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Title').fill('Account executive')
  await dialog.getByRole('button', { name: 'Add skill' }).click()
  await dialog.getByLabel('Skill 1', { exact: true }).selectOption({ label: 'Negotiation' })
  await dialog.getByLabel('Skill 1 minimum level').selectOption('4')
  await dialog.getByRole('button', { name: 'Create job role' }).click()
  await expect(roles.getByText('Negotiation (4)')).toBeVisible()

  // Ada is an employee too (she will manage Zoe).
  await nav.getByRole('link', { name: 'HRMS' }).click()
  await hrTab(page, 'Employees').click()
  await page.getByRole('button', { name: 'Hire employee' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('New person').check()
  await dialog.getByLabel('First name').fill('Ada')
  await dialog.getByLabel('Last name').fill('Owner')
  await dialog.getByLabel('Employee number').fill('E-1')
  await dialog.getByLabel('Start date').fill('2025-01-06')
  await dialog.getByLabel('Find a team member').fill('owner')
  await dialog.getByLabel('Team member account').selectOption({ label: 'Ada Owner' })
  await dialog.getByRole('button', { name: 'Hire' }).click()
  await expect(page.getByRole('heading', { name: 'Ada Owner · E-1' })).toBeVisible()

  // Zoe is hired today, reporting to Ada, with the standard onboarding.
  await hrTab(page, 'Employees').click()
  await page.getByRole('button', { name: 'Hire employee' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('New person').check()
  await dialog.getByLabel('First name').fill('Zoe')
  await dialog.getByLabel('Last name').fill('Nair')
  await dialog.getByLabel('Email').fill(hireEmail)
  await dialog.getByLabel('Employee number').fill('E-2')
  await dialog.getByLabel('Job role').selectOption({ label: 'Account executive' })
  await dialog.getByLabel('Start date').fill(iso(new Date()))
  await dialog.getByLabel('Find manager').fill('Ada')
  await dialog.getByLabel('Manager', { exact: true }).selectOption({ label: 'Ada Owner · E-1' })
  await dialog.getByLabel('Find a team member').fill('zoe')
  await dialog.getByLabel('Team member account').selectOption({ label: 'Zoe Nair' })
  await dialog.getByLabel('Onboarding').selectOption({ label: 'Standard onboarding' })
  await dialog.getByRole('button', { name: 'Hire' }).click()
  await expect(page.getByRole('heading', { name: 'Zoe Nair · E-2' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Onboarding' })).toContainText('0 of 5 tasks done')
  expect(await emailText(hireEmail, "You've been assigned: Read the employee handbook")).toContain(
    'Read the employee handbook',
  )

  // The skill gap recommends a course; HR assigns it.
  await hrTab(page, 'Training').click()
  await page.getByRole('button', { name: 'New course' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Title').fill('Advanced negotiation')
  await dialog.getByRole('button', { name: 'Add skill' }).click()
  await dialog.getByLabel('Skill 1', { exact: true }).selectOption({ label: 'Negotiation' })
  await dialog.getByLabel('Skill 1 level').selectOption('4')
  await dialog.getByRole('button', { name: 'Create course' }).click()
  await expect(page.getByRole('region', { name: 'Courses' })).toContainText('Advanced negotiation')
  await hrTab(page, 'Employees').click()
  await page.getByRole('link', { name: 'Zoe Nair' }).click()
  const gap = page.getByRole('region', { name: 'Skills and gaps' })
  await expect(gap).toContainText('needs 4 · has none')
  await gap.getByRole('button', { name: 'Assign Advanced negotiation' }).click()
  await expect(page.getByText('Training assigned.')).toBeVisible()
  expect(await emailText(hireEmail, 'Training assigned: Advanced negotiation')).toContain('Advanced negotiation')

  // Zoe signs in, completes the training (the gap closes) and asks for two days off.
  await hire.goto('/login')
  await hire.getByLabel('Workspace URL').fill(ws.slug)
  await hire.getByLabel('Email').fill(hireEmail)
  await hire.getByLabel('Password', { exact: true }).fill(PASSWORD)
  await hire.getByRole('button', { name: 'Sign in' }).click()
  await expect(hire.getByRole('heading', { name: 'Welcome, Zoe' })).toBeVisible()
  await hire.goto('/app/hrms/me')
  await expect(hire.getByRole('heading', { name: 'My HR' })).toBeVisible()
  await hire.getByRole('button', { name: 'Mark Advanced negotiation complete' }).click()
  await expect(hire.getByRole('region', { name: 'My training' })).toContainText('No training assigned.')
  const [start, end] = nextTuesdayAndWednesday()
  await hire.getByRole('button', { name: 'Request leave' }).click()
  dialog = hire.getByRole('dialog')
  await dialog.getByLabel('Leave type').selectOption({ label: 'Annual leave' })
  await dialog.getByLabel('From').fill(start)
  await dialog.getByLabel('To').fill(end)
  await dialog.getByRole('button', { name: 'Request leave' }).click()
  await expect(hire.getByText('Leave requested: 2 working day(s).')).toBeVisible()
  expect(await emailText(ws.email, `Leave request: Zoe Nair, ${start} to ${end}`)).toContain('2 working day(s)')

  // HR sees the closed gap and approves; Zoe hears about it.
  await page.reload()
  await expect(gap).toContainText('Meets every skill Account executive needs.')
  await hrTab(page, 'Leave').click()
  const queue = page.getByRole('region', { name: 'Waiting for a decision' })
  await queue.getByRole('button', { name: `Approve Zoe Nair ${start}` }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: 'Approve' }).click()
  await expect(queue.getByText('Nothing is waiting for a decision.')).toBeVisible()
  expect(await emailText(hireEmail, 'Your leave request was approved')).toContain(start)
  await expect(page.getByRole('region', { name: "Who's away" })).toContainText('Zoe Nair')

  // Zoe's balance and the overview reflect it.
  await hire.reload()
  await expect(hire.getByRole('region', { name: 'My leave' })).toContainText('18 days')
  await hrTab(page, 'Overview').click()
  const people = page.getByRole('region', { name: 'People' })
  await expect(people).toContainText('2')
  await expect(page.getByRole('region', { name: 'Needs attention' })).toContainText('Overdue trainings: 0')
  await expect(page.getByRole('region', { name: 'Away in the next 7 days' })).toContainText('Zoe Nair')
  await hireContext.close()
})
```

Notes:
- `TENANT_ADMIN` holds every HRMS permission, including `hr.self.service`, so Zoe can open My HR.
- Ada's own onboarding isn't started (no template chosen), so only Zoe's tasks exist. The HR and MANAGER items go to Ada (no email to herself); the EMPLOYEE items go to Zoe, who gets the assignment emails.
- Annual leave is 20 days. After 2 approved days the available balance reads "18 days".
- If `nextTuesdayAndWednesday()` lands on a public holiday it doesn't matter: no holidays exist in a fresh workspace.
- When the next Tuesday is more than 7 days out (today is Tuesday, so it is 7 days away), "Away in the next 7 days" still includes it: the range is today + 1 to today + 7, inclusive.

Run against a rebuilt stack: `make e2e`. Without Playwright's bundled browsers, use a temporary `frontend/playwright.chrome.config.ts` that spreads the default config and sets `channel: 'chrome'` on the project. Run `E2E_BASE_URL=http://localhost:3000 npx playwright test -c playwright.chrome.config.ts`, then delete the file. Expected: every journey passes, including `hrms.spec.ts`.

- [ ] **Step 2: README**

In `README.md`:
- Add `- HRMS (Phase 8): \`docs/superpowers/specs/2026-10-09-hrms-mvp-design.md\`` after the HelpDesk spec line.
- After the `## HelpDesk (Phase 7)` section, add:

```markdown
## HRMS (Phase 8)
Switch the module on under **Settings → Modules**; every HRMS permission switches off with it (ADR-0011, ADR-0013).

- **Employees** are directory persons with an employment record: number, department, job role, manager, type, start
  and end dates, and optionally the team member account they sign in with. Hiring can create the person in the
  directory; the directory's employee role follows the record and can't be edited by hand while HRMS manages it.
  Ending employment cancels leave and training after the last day; it can be undone.
- **Job roles** list the skills they need (levels 1–5). Every employee page shows their **skill gaps** and recommends
  courses that teach enough; completing a course raises the skill.
- **Onboarding** templates (a "Standard onboarding" is ready) turn into tasks for HR, the manager and the new hire.
- **Leave** is counted in working days (weekends and the workspace's public holidays don't count) against yearly
  balances; HR can adjust a balance. Employees ask for leave under **My HR**; their manager is emailed, approvers decide
  under **HRMS → Leave**, and the employee is emailed. Nobody decides their own request.
- **Training** assigns courses with optional due dates; overdue ones are flagged.
- **Policies** are a searchable library; published ones are readable by everyone with self-service.
- **Overview**: headcount by department, joiners and leavers, who's away, pending approvals, overdue training,
  onboarding in progress and the most common skill gaps.
- Settings → HRMS holds departments, job roles, skills, leave types, public holidays and onboarding templates.
```

- [ ] **Step 3: Run every suite, then commit**

Run: `cd backend && ./gradlew test` — Expected: PASS.
Run: `cd frontend && npx prettier --write src e2e && npm run format:check && npm run lint && npm run typecheck && npm test` — Expected: PASS.
Run: the E2E suite as above — Expected: PASS.

```bash
git add frontend/e2e README.md
git commit -m "test(e2e): the HRMS journey from hiring to approved leave; README"
```

