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
   Ending employment cancels leave starting after the last day and all open trainings.
5. **Self-service** routes act only on the employee linked to the caller.
6. Every HRMS permission belongs to module HRMS. Policies are a full-text-searchable library (the deterministic
   baseline for a later AI policy assistant).

## Consequences
- One person, one contact record; HR data stays in its module.
- A type's current allowance applies to every year (no history of allowances); HR adjusts balances by hand when needed.
- Payroll, performance, attendance, recruitment, accruals, carry-over and half days are later work (spec §1 non-goals).
