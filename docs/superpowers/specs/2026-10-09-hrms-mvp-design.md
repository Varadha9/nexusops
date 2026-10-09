# Design Spec — NexusOps HRMS MVP (Phase 8)

- **Status:** Accepted (product decisions taken on the owner's standing instruction to proceed without re-asking)
- **Date:** 2026-10-09
- **Source:** blueprint §2.4 (deeper HRMS problem: a record system instead of an operational system; the chain
  `Employee → Department → Role → Skills → Tasks → Training → Leave → Performance → Documents`), §15 (HRMS entities),
  §5 (AI must never decide hiring, firing, promotion or other high-impact employment outcomes), Phase 8 in the roadmap
  (employee profiles, departments, roles, leave, documents, onboarding, training; AI: policy assistant, document
  summarization, skill-gap insights). The conventions of the platform, canonical data model, CRM, Inventory and HelpDesk
  specs all apply (isolation, audit, errors 400 → 403 → 404 → 409, paging, full-replacement edits with `version`,
  archived-never-deleted records, module-gated permissions).

## 1. Intent

### Outcome
A small business keeps its people in the same system as its customers and work. An **employee** is the canonical
person from the directory, with an employment record: number, department, job role, manager, type, start and end
dates, and optionally the team member account they sign in with. The record is **operational**, not just a file:
- hiring starts an **onboarding checklist** of tasks for HR, the manager and the new hire;
- a job role says which **skills** it needs, so every employee shows their **skill gaps** and the **trainings** that
  close them; completing a training raises the skill;
- employees **request leave** themselves and see their balances; approvers see a queue and who is away;
- HR keeps **documents** on the employee and a searchable **policy library** everyone can read.

A dashboard shows headcount, joiners and leavers, who is away, pending approvals, overdue trainings, onboarding in
progress and the most common skill gaps.

### Success criteria (blueprint Phase 8 exit)
1. Employees, departments, job roles, skills, leave (types, holidays, requests, balances, approvals), onboarding
   checklists, training (courses, assignments, completion) and policies work end to end (database, API, UI), tenant
   isolated at both layers like every earlier table.
2. **An employee is not a second person record:** it is the directory person, whose `EMPLOYEE` role HRMS keeps in step
   with the employment (number, start date, active or not).
3. **The chain is connected:** department → job role → required skills → skill gap → recommended training → completed
   training raises the skill; hiring creates real tasks; leave is counted in working days against a balance.
4. **Self-service:** a team member linked to an employee record sees their own profile, balances, requests and
   trainings, and requests leave, without HR permissions.
5. Everything HRMS switches off with the module: every HRMS permission belongs to module `HRMS`. Every write is audited
   and permission-gated. No AI makes or suggests employment decisions.

### Non-goals (deferred)
- AI policy assistant, document summarization and AI skill-gap insights: Phase 12. This phase supplies the deterministic
  baselines they will improve on (full-text policy search, rule-based skill gaps and training recommendations).
- Payroll, compensation and benefits; performance reviews; attendance and time tracking; recruitment: later.
- Half-day leave, accrual and pro-rating of allowances, carry-over between years: later (allowances are full calendar
  years; HR can adjust any balance by hand).
- Offboarding checklists, org-chart drawing, nested departments and department heads: later (departments are flat;
  managers form the reporting line).
- Employee self-editing of their own profile and self-service access to their own documents: later.
- Scheduled reminders (overdue training, onboarding nudges): Phase 9 (workflow engine).

## 2. Approach

How an HR employee relates to the canonical directory was the main design question:

| Option | Verdict |
|---|---|
| **A. An employment record (`employees`) beside the directory person; HRMS keeps the directory's `EMPLOYEE` role in step through a module API, and the directory refuses manual edits of that role while HRMS manages it (a guard SPI, as Inventory guards product kinds)** | **Chosen.** One person, one place for contact details; HR data (department, manager, leave) stays in its module; nothing in `directory` depends on `hrms`. |
| B. Put HR fields on the directory's `EMPLOYEE` role | Couples HR's lifecycle (manager, leave, onboarding) to the directory and spreads HR permissions over directory code. |
| C. A separate HR person table | Two records for one human — the exact silo the blueprint is against. |

For working days, a pure calculator (Monday–Friday minus the workspace's public holidays) is computed on request and
stored with the request, so balances are sums, and editing holidays later doesn't silently change approved requests.

## 3. Decisions

| # | Topic | Decision |
|---|---|---|
| D1 | Module | New Modulith module `hrms`. Depends on `directory` (persons, `EMPLOYEE` role), `identity` (`Members`), `collaboration` (`SubjectResolver`, `SubjectRelations`, `TaskService` for onboarding, `Subjects`), `notifications` (`MailRequested`), `tenancy` (time zone), `audit`, `shared`. Nothing depends on `hrms`. |
| D2 | Employee | `employees`: person (a non-archived directory **person**; one employee record per person), employee number (1–40, unique ignoring case), department, job role, manager (another employee; no cycles), employment type `FULL_TIME`/`PART_TIME`/`CONTRACT`/`INTERN`, start date (required), end date (null until employment ends; ≥ start date), linked member (an active team member, at most one employee each, optional), `version`. **Status is derived** in the workspace time zone: `UPCOMING` (start > today), `ACTIVE`, `LEFT` (end < today). Hiring takes an existing person id or a new person (first name, last name, email, phone), which HRMS creates in the directory. |
| D3 | Directory sync | New `PartyService.recordEmployment(personId, employeeNumber, since, active)` (module rule, no permission check, audited `PartyRoleChanged`) sets the `EMPLOYEE` role: on hire and edit (number, start date, active), on ending employment (inactive), on undoing it (active). New SPI `directory.EmploymentGuard#beforeEmployeeRoleChange(personId)`; HRMS refuses while an employee record exists → `409 "This person's employment is managed in HRMS."`. |
| D4 | Ending employment | `POST employees/{id}/end {endDate, reason?}` sets the end date (may be past, today or future); pending and approved leave starting after the end date is cancelled; open training assignments are cancelled; the directory role turns inactive. `POST …/reinstate` clears the end date (role active again). Employees are never deleted. |
| D5 | Departments | Name (1–80, unique ignoring case), description (≤ 500), archive/restore. Archiving is refused while active or upcoming employees are in it (`409 "Move this department's employees first."`). Flat (no parent). |
| D6 | Job roles | Title (1–80, unique ignoring case), department (optional), description, **required skills** (skill + minimum level 1–5, each skill once), archive/restore (archived roles can't be chosen; employees keep them). |
| D7 | Skills | Catalog: name (1–60, unique ignoring case), archive/restore. **Employee skills:** skill + level 1–5 + optional note, set by HR (`PUT employees/{id}/skills` full replacement). Levels: 1 aware, 2 basic, 3 competent, 4 proficient, 5 expert. |
| D8 | Skill gap | Deterministic (the "skill-gap insights" baseline): for an employee with a job role, each required skill whose held level is missing or below the minimum is a gap `{skill, required, held}`; each gap lists the active courses that teach that skill at a level ≥ the required level (recommended trainings). Dashboard: the five skills with the most gaps among active employees. |
| D9 | Training | Courses: title (1–200), description, duration hours (optional, > 0), external link (optional URL), skills taught (skill + level 1–5), archive/restore. Assignments: course → employee, due date (optional), status `ASSIGNED` → `COMPLETED` (completed on, optional note) or `CANCELLED`; one open assignment per course and employee (`409`). **Overdue** = open and due date < today. Completing raises each taught skill on the employee to the course level when lower (audited). Assigning emails the employee's linked member. |
| D10 | Onboarding | Templates: name (1–80, unique), items (title 1–200, description, **owner** `HR`/`MANAGER`/`EMPLOYEE`, due offset 0–365 days from start), archive/restore; a seeded "Standard onboarding" template per workspace. Starting onboarding (on hire, optional template id, or later `POST employees/{id}/onboarding {templateId}`) creates one collaboration **task per item** on the `EMPLOYEE` subject: assignee = the hiring user (HR), the manager's linked member (MANAGER) or the employee's linked member (EMPLOYEE), falling back to the hiring user; due = start date + offset. Progress = done/total of those tasks (`employee_onboardings` remembers template and task ids). |
| D11 | Leave types | Name (1–60, unique), paid (bool), **annual allowance** in days (0–366, or null = unlimited, no balance check), archive/restore. Seeded: Annual leave (paid, 20), Sick leave (paid, 10), Unpaid leave (unpaid, unlimited). |
| D12 | Holidays | Per workspace: date (unique) + name (1–80); add/remove (no edits). Used only when a request is created or edited. |
| D13 | Leave requests | Employee, type, start and end date (whole days; start ≤ end; the same calendar year — `400 "Split a request at the end of the year."`), reason (≤ 500). **Working days** = Mon–Fri in range minus holidays, stored on the request; must be ≥ 1 (`400 "Choose at least one working day."`). No overlap with the employee's own pending or approved requests (`409`). Not before the start date or after the end date of employment (`400`). Status `PENDING` → `APPROVED`/`REJECTED` (decision note, decided by, decided at) or `CANCELLED`. Over-balance requests are refused (`409 "Not enough leave left: N days available."`). Requester: the employee themself (self-service) or anyone with `hr.leave.approve` on their behalf. Edits: none — cancel and request again. Cancel: the requester while pending, or before the start date when approved; approvers any time before the end date. An approver can't decide their own request (`409 "Someone else must decide your own request."`). Emails: on request to the manager's linked member (if any); on decision to the employee's linked member. |
| D14 | Balances | Per employee, type and calendar year: `entitlement = allowance + Σ adjustments`, `taken = Σ approved days`, `pending = Σ pending days`, `available = entitlement − taken − pending` (null for unlimited types). Adjustments: `POST employees/{id}/leave-adjustments {typeId, year, days (a non-zero multiple of 0.5, at most 366 either way), reason (1–200)}`, append-only. |
| D15 | Documents | Employee documents are collaboration documents on the `EMPLOYEE` subject (existing panel, readable with `hr.employee.read`). |
| D16 | Policies | `hr_policies`: title (1–200), body (1–50 000, plain text), status `DRAFT`/`PUBLISHED`/`ARCHIVED` (same rules as knowledge-base articles), published at, author; full-text search (`'simple'`, title weight A, body B) — the "policy assistant" baseline. Published policies are readable with `hr.self.service` or `hr.policy.manage`; drafts and archived only with `hr.policy.manage`. |
| D17 | Self-service | `GET hr/me` → the employee record linked to the current user (404 `"Your account isn't linked to an employee record."` when none) with balances for the current year, own requests, open trainings and onboarding progress; `POST hr/me/leave-requests`; `POST hr/me/leave-requests/{id}/cancel`; `POST hr/me/training/{assignmentId}/complete`. Needs `hr.self.service` only. |
| D18 | Collaboration | `EMPLOYEE` subject (label `Name · number`, archived when `LEFT`, search by name and number, read permission `hr.employee.read`); a person's timeline includes their employee record. Search order gains `EMPLOYEE` after `KB_ARTICLE`. |
| D19 | Dashboard | `GET hr/dashboard`: active headcount, by department, upcoming starts (next 30 days), joiners and leavers in the last 90 days, away today and in the next 7 days (approved leave), pending leave requests, overdue training assignments, employees with onboarding in progress, top 5 skill gaps. |
| D20 | Permissions | Module `HRMS`: `hr.employee.read` (kept: employees, departments, job roles, skills, leave types, holidays, templates, dashboard), `hr.employee.update` (kept: hire, edit, end/reinstate, skills, start onboarding, leave adjustments), `hr.settings.manage` (new: departments, job roles, skills, leave types, holidays, onboarding templates), `hr.leave.approve` (new: see all requests, request on behalf, approve/reject/cancel), `hr.training.manage` (new: courses, assign, complete, cancel), `hr.policy.manage` (new), `hr.self.service` (new). System roles get every new code by migration. |
| D21 | UI | HRMS nav entry (replacing "coming in Phase 8") with tabs: Overview, Employees, Leave, Training, Policies; **My HR** page for self-service. Employee page: profile and employment, end/reinstate, skills with gaps and recommended trainings, trainings, leave balances and requests, onboarding progress, plus Tasks, Documents and Activity panels. Leave tab: approval queue, all requests, who's away. Settings → HRMS: departments, job roles (with required skills), skills, leave types, holidays, onboarding templates. A person's directory page links to their employee record. |
| D22 | Messages | Verbatim: `"Record not found."`, `"This record is archived."`, `"This record was changed by someone else. Reload and try again."`, `"You do not have permission to perform this action."`, `"Choose a person in this workspace."`, `"This person already has an employee record."`, `"Choose an active team member."`, `"This team member is already linked to an employee."`, `"A manager can't report to their own report."`, `"This person's employment is managed in HRMS."`, `"Move this department's employees first."`, `"Split a request at the end of the year."`, `"Choose at least one working day."`, `"This overlaps another leave request."`, `"Not enough leave left: N days available."`, `"Someone else must decide your own request."`, `"Your account isn't linked to an employee record."`. |

## 4. Data model (Flyway V28–V33)

All tables are tenant-owned with `ENABLE`/`FORCE ROW LEVEL SECURITY` and the standard `tenant_isolation` policy in
`USING` and `WITH CHECK`, created in the same migration, `UNIQUE (tenant_id, id)` for composite FKs, member references
`REFERENCES users (id) ON DELETE SET NULL`.

| Migration | Contents |
|---|---|
| V28 HR base | Permissions (D20); `departments`, `skills`, `job_roles`, `job_role_skills`; seeds none. |
| V29 employees | `employees` (D2) with composite FKs to parties, departments, job roles, self (manager); partial unique on `user_id`; `employee_skills`. |
| V30 onboarding | `onboarding_templates`, `onboarding_template_items`, `employee_onboardings`, `employee_onboarding_tasks`; seed "Standard onboarding" for existing workspaces. |
| V31 leave | `leave_types`, `holidays`, `leave_requests` (CHECKs on dates, status↔decision fields), `leave_adjustments` (append-only); seed leave types for existing workspaces. |
| V32 training | `courses`, `course_skills`, `training_assignments` (partial unique open assignment per course+employee). |
| V33 policies | `hr_policies` with generated `tsvector` + GIN index. |

## 5. API (`/api/v1/hr`)

| Route | Authorization |
|---|---|
| `GET employees?q=&status=&departmentId=&jobRoleId=&managerId=&personId=&page=&size=`, `GET employees/{id}`, `GET employees/{id}/skill-gap`, `GET employees/{id}/leave-balances?year=` | `hr.employee.read` |
| `GET members?q=` (active team members to link), `POST employees`, `PUT employees/{id}`, `POST employees/{id}/end`, `POST …/reinstate`, `PUT …/skills`, `POST …/onboarding`, `POST …/leave-adjustments` | `hr.employee.update` |
| `GET departments`, `GET job-roles`, `GET skills`, `GET leave-types`, `GET holidays?year=`, `GET onboarding-templates` (each `?archived=`) | `hr.employee.read` (leave types and holidays also with `hr.self.service`) |
| Writes on those, `POST …/archive|restore` | `hr.settings.manage` |
| `GET leave-requests?status=&employeeId=&from=&to=`, `POST leave-requests`, `POST leave-requests/{id}/approve|reject|cancel`, `GET leave/away?from=&to=` | `hr.leave.approve` |
| `GET courses`, `GET training-assignments?employeeId=&status=&overdue=` | `hr.employee.read` |
| Course writes, `POST training-assignments`, `POST training-assignments/{id}/complete|cancel` | `hr.training.manage` |
| `GET policies?q=&status=`, `GET policies/{id}` | `hr.self.service` or `hr.policy.manage` (D16) |
| Policy writes, `POST policies/{id}/publish|unpublish|archive` | `hr.policy.manage` |
| `GET me`, `POST me/leave-requests`, `POST me/leave-requests/{id}/cancel`, `POST me/training/{id}/complete` | `hr.self.service` |
| `GET dashboard` | `hr.employee.read` |

## 6. Security and isolation

- The new tables join `RlsCoverageIT.EXPECTED_TENANT_TABLES`; a raw-JDBC test proves tenant isolation for every table
  and that `leave_adjustments` is append-only for the app role.
- `CrossTenantApiIT` covers every new id-bearing route and body reference; a module-gate test covers every HRMS handler.
- Self-service endpoints act only on the employee linked to the caller; ids of other employees' requests or
  assignments answer `404`.
- Employment data never appears in the directory's party views beyond the existing `EMPLOYEE` role (still behind
  `directory.employee.read`).
- No AI is involved; nothing ranks or recommends people — only trainings for an employee's own gaps.

## 7. Testing

- **Unit:** working-day calculator (weekends, holidays, single day, all-weekend range), leave balance arithmetic,
  employee status derivation, skill-gap computation, manager-cycle detection.
- **Integration:** every route's happy path and 400/403/404/409 branches; hiring a new or existing person syncs the
  directory role and the guard refuses directory edits; ending employment cancels future leave and open trainings;
  onboarding creates tasks with the right assignees and due dates; leave overlap, balance, year-split, own-decision and
  cancellation rules; emails to manager and employee; completing a course raises skills; policies visibility; self-service
  only reaches the caller's own record; dashboard figures; module-off 403s.
- **Isolation:** RLS coverage, raw-JDBC isolation, cross-tenant API suite, handler-level permission gate.
- **Frontend:** Vitest page tests against `fakeServer`.
- **E2E:** enable HRMS → set up a job role needing a skill and a course teaching it → hire a new person with a linked
  team member and the standard onboarding (tasks appear, assignment email in Mailpit) → the employee page shows the
  skill gap and recommends the course → assign it → the new hire signs in, sees My HR, completes the training (gap
  closes) and requests two days of leave (manager's email in Mailpit) → HR approves (employee email) → balances and
  "away" reflect it → dashboard shows the headcount and nobody overdue.
