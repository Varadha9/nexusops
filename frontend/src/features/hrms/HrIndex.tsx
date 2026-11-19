import { Navigate } from 'react-router'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { TABS } from './HrLayout'
import { HrOverviewPage } from './HrOverviewPage'

/** The overview for employee readers, My HR for self-service users; anyone else lands on the first section they may open. */
export function HrIndex() {
  const can = useCan()
  if (can(PERMISSIONS.hrEmployeeRead)) return <HrOverviewPage />
  if (can(PERMISSIONS.hrSelf)) return <Navigate to="/app/hrms/me" replace />
  const first = TABS.find((tab) => can(...tab.anyOf))
  return first ? <Navigate to={first.to} replace /> : null
}
