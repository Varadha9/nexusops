import type { RouteObject } from 'react-router'
import { PERMISSIONS, RequirePermission } from '@/features/auth/permissions'
import { EmployeeDetailPage } from './EmployeeDetailPage'
import { EmployeesPage } from './EmployeesPage'
import { HrIndex } from './HrIndex'

/** /app/hrms/* children (Tasks 10–11 add theirs). */
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
    path: 'employees/:employeeId',
    element: (
      <RequirePermission anyOf={[PERMISSIONS.hrEmployeeRead]}>
        <EmployeeDetailPage />
      </RequirePermission>
    ),
  },
]
