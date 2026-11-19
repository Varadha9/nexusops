import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { PolicyAssistantCard } from '@/features/ai/PolicyAssistantCard'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { problemMessage } from '@/lib/api/problems'
import type {
  LeaveRequestView,
  MeView,
  Page,
  PolicySummary,
  TrainingAssignmentView,
} from '@/lib/api/types'
import { formatDate, todayIso } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { invalidateHr } from './invalidation'
import { formatDays, LEAVE_STATUS_LABELS } from './labels'
import { LeaveRequestDialog } from './LeaveRequestDialog'

/** Self-service: my leave, my training, my onboarding and the policies. */
export function MyHrPage() {
  const api = useApi()
  const can = useCan()
  const me = useQuery({
    queryKey: ['hr', 'me'],
    queryFn: () => api.get<MeView>('/hr/me'),
    retry: false,
  })
  const notLinked = me.isError && me.error instanceof ApiError && me.error.status === 404

  if (me.isPending) return <ListSkeleton />
  if (notLinked)
    return (
      <>
        <PageHeader title="My HR" description="Your leave, training and the company policies." />
        <div className="rounded-lg border border-dashed p-6 text-center">
          <p className="font-medium">{problemMessage(me.error)}</p>
          <p className="mt-1 text-sm text-muted-foreground">
            Ask HR to link your account to your employee record.
          </p>
        </div>
      </>
    )
  if (me.isError)
    return (
      <>
        <PageHeader title="My HR" description="Your leave, training and the company policies." />
        <ErrorState error={me.error} onRetry={() => void me.refetch()} />
      </>
    )

  const { employee } = me.data
  return (
    <>
      <PageHeader
        title="My HR"
        description={`${employee.person.name} · ${employee.employeeNumber} · ${employee.jobRole?.title ?? 'No job role'}`}
      />
      <div className="space-y-6">
        <MyLeave me={me.data} />
        <MyTraining me={me.data} />
        {employee.onboarding && (
          <section aria-label="My onboarding" className="space-y-3">
            <h2 className="text-base font-semibold">My onboarding</h2>
            <p className="text-sm">
              {employee.onboarding.done} of {employee.onboarding.total} tasks done
            </p>
            {can(PERMISSIONS.taskRead) ? (
              <Link
                to="/app/tasks?assignee=me"
                className="text-sm underline-offset-4 hover:underline"
              >
                See my tasks
              </Link>
            ) : (
              <p className="text-sm text-muted-foreground">
                Your manager or HR will tick these off.
              </p>
            )}
          </section>
        )}
        <PolicyAssistantCard />
        <Policies />
      </div>
    </>
  )
}

function MyLeave({ me }: { me: MeView }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [requesting, setRequesting] = useState(false)
  const [cancelling, setCancelling] = useState<LeaveRequestView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const today = todayIso()

  async function cancel(request: LeaveRequestView) {
    setBusy(true)
    setError(null)
    try {
      await api.post(`/hr/me/leave-requests/${request.id}/cancel`, { version: request.version })
      await invalidateHr(queryClient)
      toast.success('Leave cancelled.')
      setCancelling(null)
    } catch (e) {
      setError(problemMessage(e))
      void invalidateHr(queryClient)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-label="My leave" className="space-y-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-base font-semibold">My leave</h2>
        <Button size="sm" onClick={() => setRequesting(true)}>
          Request leave
        </Button>
      </div>
      <ul aria-label={`Balances for ${me.year}`} className="divide-y rounded-lg border text-sm">
        {me.balances.map((b) => (
          <li
            key={b.type.id}
            className="flex flex-wrap items-center justify-between gap-2 px-3 py-2"
          >
            <span className="font-medium">{b.type.name}</span>
            <span className="text-muted-foreground">
              {formatDays(b.available)}
              {b.entitlement != null && ` of ${formatDays(b.entitlement)}`}
            </span>
          </li>
        ))}
      </ul>
      {me.requests.length === 0 ? (
        <p className="text-sm text-muted-foreground">No leave requests.</p>
      ) : (
        <ul aria-label="My requests" className="divide-y rounded-lg border text-sm">
          {me.requests.map((r) => (
            <li key={r.id} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2">
              <span>
                {formatDate(r.startDate)} – {formatDate(r.endDate)} · {r.type.name} ·{' '}
                {formatDays(r.days)}
              </span>
              <span className="flex items-center gap-2">
                <Badge variant={r.status === 'PENDING' ? 'secondary' : 'outline'}>
                  {LEAVE_STATUS_LABELS[r.status]}
                </Badge>
                {(r.status === 'PENDING' || (r.status === 'APPROVED' && r.startDate > today)) && (
                  <Button
                    size="sm"
                    variant="outline"
                    aria-label={`Cancel leave ${r.startDate}`}
                    onClick={() => {
                      setError(null)
                      setCancelling(r)
                    }}
                  >
                    Cancel
                  </Button>
                )}
              </span>
            </li>
          ))}
        </ul>
      )}
      {requesting && (
        <LeaveRequestDialog
          self
          onClose={() => setRequesting(false)}
          onSaved={() => setRequesting(false)}
        />
      )}
      <ConfirmDialog
        open={cancelling !== null}
        title="Cancel this leave?"
        description={
          cancelling
            ? `${formatDate(cancelling.startDate)} – ${formatDate(cancelling.endDate)}. Your days go back to the balance.`
            : ''
        }
        confirmLabel="Cancel leave"
        busy={busy}
        error={error}
        onCancel={() => setCancelling(null)}
        onConfirm={() => cancelling && void cancel(cancelling)}
      />
    </section>
  )
}

function MyTraining({ me }: { me: MeView }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [completing, setCompleting] = useState<TrainingAssignmentView | null>(null)
  const open = me.trainings.filter((t) => t.status === 'ASSIGNED')

  async function complete(training: TrainingAssignmentView) {
    setBusy(true)
    setError(null)
    try {
      await api.post(`/hr/me/training/${training.id}/complete`, {
        note: null,
        version: training.version,
      })
      await invalidateHr(queryClient)
      toast.success('Training completed.')
      setCompleting(null)
    } catch (e) {
      setError(problemMessage(e))
      void invalidateHr(queryClient)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-label="My training" className="space-y-3">
      <h2 className="text-base font-semibold">My training</h2>
      {open.length === 0 ? (
        <p className="text-sm text-muted-foreground">No training assigned.</p>
      ) : (
        <ul className="divide-y rounded-lg border text-sm">
          {open.map((t) => (
            <li key={t.id} className="flex flex-wrap items-center justify-between gap-2 px-3 py-2">
              <span className="font-medium">{t.course.title}</span>
              <span className="flex items-center gap-2 text-muted-foreground">
                {t.dueOn ? `Due ${formatDate(t.dueOn)}` : 'No due date'}
                {t.overdue && <Badge variant="destructive">Overdue</Badge>}
                <Button
                  size="sm"
                  disabled={busy}
                  aria-label={`Mark ${t.course.title} complete`}
                  onClick={() => {
                    setError(null)
                    setCompleting(t)
                  }}
                >
                  Mark complete
                </Button>
              </span>
            </li>
          ))}
        </ul>
      )}
      <ConfirmDialog
        open={completing !== null}
        title={completing ? `Mark ${completing.course.title} complete?` : ''}
        description="This raises your skills and can't be undone."
        confirmLabel="Mark complete"
        busy={busy}
        error={error}
        onCancel={() => setCompleting(null)}
        onConfirm={() => completing && void complete(completing)}
      />
    </section>
  )
}

function Policies() {
  const api = useApi()
  const policies = useQuery({
    queryKey: ['hr', 'policies', { size: 10 }],
    queryFn: () => api.get<Page<PolicySummary>>(`/hr/policies?${toQuery({ size: 10 })}`),
  })
  return (
    <section aria-label="Policies" className="space-y-3">
      <div className="flex items-center justify-between gap-2">
        <h2 className="text-base font-semibold">Policies</h2>
        <Link to="/app/hrms/policies" className="text-sm underline-offset-4 hover:underline">
          All policies
        </Link>
      </div>
      {policies.isPending ? (
        <ListSkeleton rows={2} />
      ) : policies.isError ? (
        <ErrorState error={policies.error} onRetry={() => void policies.refetch()} />
      ) : policies.data.items.length === 0 ? (
        <p className="text-sm text-muted-foreground">No policies have been published yet.</p>
      ) : (
        <ul className="divide-y rounded-lg border text-sm">
          {policies.data.items.map((p) => (
            <li key={p.id} className="px-3 py-2">
              <Link
                to={`/app/hrms/policies/${p.id}`}
                className="font-medium underline-offset-4 hover:underline"
              >
                {p.title}
              </Link>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
