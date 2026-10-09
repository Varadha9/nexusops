import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { ActivityPanel } from '@/features/records/ActivityPanel'
import { DocumentsPanel } from '@/features/records/DocumentsPanel'
import { SubjectTasksPanel } from '@/features/records/SubjectTasksPanel'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { EmployeeView } from '@/lib/api/types'
import { formatDate } from '@/lib/format'
import { EmployeeFormDialog, employeeKey } from './EmployeeFormDialog'
import { EndEmploymentDialog } from './EndEmploymentDialog'
import { invalidateHr } from './invalidation'
import { EMPLOYMENT_STATUS_LABELS, EMPLOYMENT_TYPE_LABELS } from './labels'
import { OnboardingCard } from './OnboardingCard'
import { SkillGapCard } from './SkillGapCard'

function EmploymentCard({ employee: e }: { employee: EmployeeView }) {
  return (
    <Card role="region" aria-label="Employment">
      <CardHeader>
        <CardTitle>Employment</CardTitle>
      </CardHeader>
      <CardContent className="text-sm">
        <dl className="grid grid-cols-[8rem_1fr] gap-x-3 gap-y-2">
          <dt className="text-muted-foreground">Person</dt>
          <dd>
            <Link
              to={`/app/directory/${e.person.id}`}
              className="underline-offset-4 hover:underline"
            >
              {e.person.name}
            </Link>
            {e.person.email && <div>{e.person.email}</div>}
            {e.person.phone && <div>{e.person.phone}</div>}
          </dd>
          <dt className="text-muted-foreground">Number</dt>
          <dd>{e.employeeNumber}</dd>
          <dt className="text-muted-foreground">Department</dt>
          <dd>{e.department?.name ?? '—'}</dd>
          <dt className="text-muted-foreground">Job role</dt>
          <dd>{e.jobRole?.title ?? '—'}</dd>
          <dt className="text-muted-foreground">Manager</dt>
          <dd>
            {e.manager ? (
              <Link
                to={`/app/hrms/employees/${e.manager.id}`}
                className="underline-offset-4 hover:underline"
              >
                {e.manager.name}
              </Link>
            ) : (
              '—'
            )}
          </dd>
          <dt className="text-muted-foreground">Type</dt>
          <dd>{EMPLOYMENT_TYPE_LABELS[e.employmentType]}</dd>
          <dt className="text-muted-foreground">Started</dt>
          <dd>{formatDate(e.startDate)}</dd>
          <dt className="text-muted-foreground">Account</dt>
          <dd>{e.user?.name ?? 'Not linked'}</dd>
        </dl>
      </CardContent>
    </Card>
  )
}

/** The route reuses this page when only :employeeId changes, so the body is keyed: an open dialog never follows the user to another employee. */
export function EmployeeDetailPage() {
  const { employeeId = '' } = useParams()
  return <EmployeeDetail key={employeeId} employeeId={employeeId} />
}

function EmployeeDetail({ employeeId }: { employeeId: string }) {
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const [dialog, setDialog] = useState<'edit' | 'end' | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const employee = useQuery({
    queryKey: employeeKey(employeeId),
    queryFn: () => api.get<EmployeeView>(`/hr/employees/${employeeId}`),
  })

  const back = (
    <Link to="/app/hrms/employees" className="text-sm underline-offset-4 hover:underline">
      ← Employees
    </Link>
  )
  if (employee.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (employee.isError)
    return (
      <>
        {back}
        <ErrorState error={employee.error} onRetry={() => void employee.refetch()} />
      </>
    )

  const e = employee.data
  const canEdit = can(PERMISSIONS.hrEmployeeUpdate)
  const title = `${e.person.name} · ${e.employeeNumber}`

  async function reinstate() {
    setBusy(true)
    setError(null)
    try {
      const saved = await api.post<EmployeeView>(`/hr/employees/${e.id}/reinstate`, {
        version: e.version,
      })
      queryClient.setQueryData(employeeKey(saved.id), saved)
      await invalidateHr(queryClient)
      toast.success(`${saved.person.name} reinstated.`)
    } catch (err) {
      setError(problemMessage(err))
      void queryClient.invalidateQueries({ queryKey: employeeKey(e.id) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={title}
        description={`${e.jobRole?.title ?? 'No job role'} · ${e.department?.name ?? 'No department'}`}
        actions={
          <>
            <Badge variant={e.status === 'LEFT' ? 'outline' : 'secondary'}>
              {EMPLOYMENT_STATUS_LABELS[e.status]}
            </Badge>
            {canEdit && (
              <>
                <Button variant="outline" size="sm" onClick={() => setDialog('edit')}>
                  Edit
                </Button>
                {e.endDate === null ? (
                  <Button variant="outline" size="sm" onClick={() => setDialog('end')}>
                    End employment
                  </Button>
                ) : (
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={busy}
                    onClick={() => void reinstate()}
                  >
                    Reinstate
                  </Button>
                )}
              </>
            )}
          </>
        }
      />
      {error && (
        <p role="alert" className="text-sm text-destructive">
          {error}
        </p>
      )}
      {e.endDate && e.status !== 'LEFT' && (
        <p role="status" className="text-sm text-muted-foreground">
          Leaving on {formatDate(e.endDate)}
          {e.endReason ? ` — ${e.endReason}` : ''}
        </p>
      )}
      {e.endDate && e.status === 'LEFT' && (
        <p role="status" className="text-sm text-muted-foreground">
          Left on {formatDate(e.endDate)}
          {e.endReason ? ` — ${e.endReason}` : ''}
        </p>
      )}
      <div className="grid gap-4 lg:grid-cols-2">
        <EmploymentCard employee={e} />
        <SkillGapCard employee={e} />
        <OnboardingCard employee={e} />
        {/* leave */}
        {/* training */}
      </div>
      <div id="tasks">
        <SubjectTasksPanel
          subjectType="EMPLOYEE"
          subjectId={e.id}
          label={title}
          archived={e.status === 'LEFT'}
        />
      </div>
      <ActivityPanel subjectType="EMPLOYEE" subjectId={e.id} archived={e.status === 'LEFT'} />
      <DocumentsPanel subjectType="EMPLOYEE" subjectId={e.id} archived={e.status === 'LEFT'} />
      {dialog === 'edit' && (
        <EmployeeFormDialog
          employee={e}
          onClose={() => setDialog(null)}
          onSaved={() => setDialog(null)}
        />
      )}
      {dialog === 'end' && <EndEmploymentDialog employee={e} onClose={() => setDialog(null)} />}
    </div>
  )
}
