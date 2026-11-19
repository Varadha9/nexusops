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
