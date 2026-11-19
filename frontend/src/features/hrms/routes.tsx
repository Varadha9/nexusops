import type { RouteObject } from 'react-router'
import { PERMISSIONS, RequirePermission } from '@/features/auth/permissions'
import { EmployeeDetailPage } from './EmployeeDetailPage'
import { EmployeesPage } from './EmployeesPage'
import { HrIndex } from './HrIndex'
import { LeavePage } from './LeavePage'
import { MyHrPage } from './MyHrPage'
import { PoliciesPage } from './PoliciesPage'
import { PolicyDetailPage } from './PolicyDetailPage'
import { TrainingPage } from './TrainingPage'

/** /app/hrms/* children. */
export const hrmsChildren: RouteObject[] = [
  { index: true, element: <HrIndex /> },
  {
    path: 'employees',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.hrEmployeeRead]}>
        <EmployeesPage />
      </RequirePermission>
    ),
  },
  {
    path: 'leave',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.hrLeaveApprove]}>
        <LeavePage />
      </RequirePermission>
    ),
  },
  {
    path: 'employees/:employeeId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.hrEmployeeRead]}>
        <EmployeeDetailPage />
      </RequirePermission>
    ),
  },
  {
    path: 'training',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.hrEmployeeRead]}>
        <TrainingPage />
      </RequirePermission>
    ),
  },
  {
    path: 'policies',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.hrSelf, PERMISSIONS.hrPolicy]}>
        <PoliciesPage />
      </RequirePermission>
    ),
  },
  {
    path: 'policies/:policyId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.hrSelf, PERMISSIONS.hrPolicy]}>
        <PolicyDetailPage />
      </RequirePermission>
    ),
  },
  {
    path: 'me',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.hrSelf]}>
        <MyHrPage />
      </RequirePermission>
    ),
  },
]
