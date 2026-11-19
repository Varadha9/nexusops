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
      <Link
        to={`/app/hrms/employees/${employee.id}`}
        className="underline-offset-4 hover:underline"
      >
        View employee record
      </Link>
    </p>
  )
}
