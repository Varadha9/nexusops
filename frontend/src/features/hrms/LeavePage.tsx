import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link } from 'react-router'
import { toast } from 'sonner'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { AwayView, LeaveRequestView, LeaveStatus, Page } from '@/lib/api/types'
import { formatDate, formatDateTime, todayIso } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { DecisionDialog } from './DecisionDialog'
import { EmployeePicker } from './EmployeePicker'
import { invalidateHr } from './invalidation'
import { formatDays, LEAVE_STATUS_LABELS } from './labels'
import { LeaveRequestDialog } from './LeaveRequestDialog'

const SIZE = 20

/** Today plus `days`, as YYYY-MM-DD in local time. */
function inDays(days: number): string {
  const [y, m, d] = todayIso().split('-').map(Number)
  const later = new Date(y, m - 1, d + days)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${later.getFullYear()}-${pad(later.getMonth() + 1)}-${pad(later.getDate())}`
}

function employeeLink(request: { employee: { id: string; name: string } }) {
  return (
    <Link
      to={`/app/hrms/employees/${request.employee.id}`}
      className="font-medium underline-offset-4 hover:underline"
    >
      {request.employee.name}
    </Link>
  )
}

export function LeavePage() {
  const [requesting, setRequesting] = useState(false)
  return (
    <>
      <PageHeader
        title="Leave"
        description="Approve requests, see everyone's leave and who is away."
        actions={<Button onClick={() => setRequesting(true)}>Request leave for someone</Button>}
      />
      <div className="space-y-6">
        <WaitingForDecision />
        <AllRequests />
        <WhoIsAway />
      </div>
      {requesting && (
        <LeaveRequestDialog
          onClose={() => setRequesting(false)}
          onSaved={() => setRequesting(false)}
        />
      )}
    </>
  )
}

function WaitingForDecision() {
  const api = useApi()
  const [deciding, setDeciding] = useState<{
    request: LeaveRequestView
    outcome: 'approve' | 'reject'
  } | null>(null)
  const [page, setPage] = useState(0)
  const pending = useQuery({
    queryKey: ['hr', 'leave-requests', { status: 'PENDING', page }],
    queryFn: () =>
      api.get<Page<LeaveRequestView>>(
        `/hr/leave-requests?${toQuery({ status: 'PENDING', page, size: SIZE })}`,
      ),
    placeholderData: keepPreviousData,
  })
  return (
    <section aria-label="Waiting for a decision" className="space-y-3">
      <h2 className="text-base font-semibold">Waiting for a decision</h2>
      {pending.isPending ? (
        <ListSkeleton rows={3} />
      ) : pending.isError ? (
        <ErrorState error={pending.error} onRetry={() => void pending.refetch()} />
      ) : pending.data.items.length === 0 && page === 0 ? (
        <p className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
          Nothing is waiting for a decision.
        </p>
      ) : pending.data.items.length === 0 ? (
        <>
          <p className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
            Nothing more on this page.
          </p>
          <Pagination
            page={pending.data.page}
            size={pending.data.size}
            total={pending.data.total}
            onPage={setPage}
          />
        </>
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Employee</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead>Dates</TableHead>
                  <TableHead>Days</TableHead>
                  <TableHead>Reason</TableHead>
                  <TableHead>Requested</TableHead>
                  <TableHead>
                    <span className="sr-only">Decide</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {pending.data.items.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell>{employeeLink(r)}</TableCell>
                    <TableCell>{r.type.name}</TableCell>
                    <TableCell>
                      {formatDate(r.startDate)} – {formatDate(r.endDate)}
                    </TableCell>
                    <TableCell>{formatDays(r.days)}</TableCell>
                    <TableCell>{r.reason ?? '—'}</TableCell>
                    <TableCell>{formatDateTime(r.requestedAt)}</TableCell>
                    <TableCell className="space-x-2 whitespace-nowrap">
                      <Button
                        size="sm"
                        aria-label={`Approve ${r.employee.name} ${r.startDate}`}
                        onClick={() => setDeciding({ request: r, outcome: 'approve' })}
                      >
                        Approve
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        aria-label={`Reject ${r.employee.name} ${r.startDate}`}
                        onClick={() => setDeciding({ request: r, outcome: 'reject' })}
                      >
                        Reject
                      </Button>
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={pending.data.page}
            size={pending.data.size}
            total={pending.data.total}
            onPage={setPage}
          />
        </>
      )}
      {deciding && (
        <DecisionDialog
          request={deciding.request}
          outcome={deciding.outcome}
          onClose={() => setDeciding(null)}
        />
      )}
    </section>
  )
}

function AllRequests() {
  const api = useApi()
  const queryClient = useQueryClient()
  const [status, setStatus] = useState('')
  const [employeeId, setEmployeeId] = useState('')
  const [page, setPage] = useState(0)
  const [cancelling, setCancelling] = useState<LeaveRequestView | null>(null)
  const [cancelError, setCancelError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const today = todayIso()
  const requests = useQuery({
    queryKey: ['hr', 'leave-requests', { status, employeeId, page }],
    queryFn: () =>
      api.get<Page<LeaveRequestView>>(
        `/hr/leave-requests?${toQuery({ status, employeeId, page, size: SIZE })}`,
      ),
    placeholderData: keepPreviousData,
  })

  async function cancel(request: LeaveRequestView) {
    setBusy(true)
    setCancelError(null)
    try {
      await api.post<LeaveRequestView>(`/hr/leave-requests/${request.id}/cancel`, {
        version: request.version,
      })
      await invalidateHr(queryClient)
      toast.success('Leave cancelled.')
      setCancelling(null)
    } catch (error) {
      setCancelError(problemMessage(error))
      void invalidateHr(queryClient)
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-label="All requests" className="space-y-3">
      <h2 className="text-base font-semibold">All requests</h2>
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="leave-filter-status">Status</Label>
          <NativeSelect
            id="leave-filter-status"
            value={status}
            onChange={(e) => {
              setStatus(e.target.value)
              setPage(0)
            }}
          >
            <option value="">Any status</option>
            {(Object.keys(LEAVE_STATUS_LABELS) as LeaveStatus[]).map((s) => (
              <option key={s} value={s}>
                {LEAVE_STATUS_LABELS[s]}
              </option>
            ))}
          </NativeSelect>
        </div>
        <EmployeePicker
          id="leave-filter-employee"
          label="Employee"
          blankLabel="Anyone"
          value={employeeId}
          onChange={(id) => {
            setEmployeeId(id)
            setPage(0)
          }}
        />
      </div>
      {requests.isPending ? (
        <ListSkeleton />
      ) : requests.isError ? (
        <ErrorState error={requests.error} onRetry={() => void requests.refetch()} />
      ) : requests.data.items.length === 0 ? (
        <p className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
          No leave requests match.
        </p>
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Employee</TableHead>
                  <TableHead>Type</TableHead>
                  <TableHead>Dates</TableHead>
                  <TableHead>Days</TableHead>
                  <TableHead>Status</TableHead>
                  <TableHead>
                    <span className="sr-only">Actions</span>
                  </TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {requests.data.items.map((r) => (
                  <TableRow key={r.id}>
                    <TableCell>{employeeLink(r)}</TableCell>
                    <TableCell>{r.type.name}</TableCell>
                    <TableCell>
                      {formatDate(r.startDate)} – {formatDate(r.endDate)}
                    </TableCell>
                    <TableCell>{formatDays(r.days)}</TableCell>
                    <TableCell>
                      <Badge variant={r.status === 'PENDING' ? 'secondary' : 'outline'}>
                        {LEAVE_STATUS_LABELS[r.status]}
                      </Badge>
                    </TableCell>
                    <TableCell>
                      {(r.status === 'PENDING' || r.status === 'APPROVED') &&
                        r.endDate >= today && (
                          <Button
                            size="sm"
                            variant="outline"
                            aria-label={`Cancel ${r.employee.name} ${r.startDate}`}
                            onClick={() => {
                              setCancelError(null)
                              setCancelling(r)
                            }}
                          >
                            Cancel
                          </Button>
                        )}
                    </TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={requests.data.page}
            size={requests.data.size}
            total={requests.data.total}
            onPage={setPage}
          />
        </>
      )}
      <ConfirmDialog
        open={cancelling !== null}
        title="Cancel this leave?"
        description={
          cancelling
            ? `${cancelling.employee.name}, ${formatDate(cancelling.startDate)} – ${formatDate(cancelling.endDate)}. Their days go back to the balance.`
            : ''
        }
        confirmLabel="Cancel leave"
        busy={busy}
        error={cancelError}
        onCancel={() => setCancelling(null)}
        onConfirm={() => cancelling && void cancel(cancelling)}
      />
    </section>
  )
}

function WhoIsAway() {
  const api = useApi()
  const [from, setFrom] = useState(() => todayIso())
  const [to, setTo] = useState(() => inDays(13))
  const away = useQuery({
    queryKey: ['hr', 'away', from, to],
    queryFn: () => api.get<AwayView[]>(`/hr/leave/away?${toQuery({ from, to })}`),
    enabled: Boolean(from && to && to >= from),
  })
  return (
    <section aria-label="Who's away" className="space-y-3">
      <h2 className="text-base font-semibold">Who's away</h2>
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="away-from">From</Label>
          <Input
            id="away-from"
            type="date"
            value={from}
            onChange={(e) => setFrom(e.target.value)}
          />
        </div>
        <div className="space-y-1.5">
          <Label htmlFor="away-to">To</Label>
          <Input id="away-to" type="date" value={to} onChange={(e) => setTo(e.target.value)} />
        </div>
      </div>
      {!from || !to ? (
        <p className="text-sm text-muted-foreground">Choose both dates.</p>
      ) : to < from ? (
        <p className="text-sm text-muted-foreground">
          The end date can't be before the start date.
        </p>
      ) : away.isPending ? (
        <ListSkeleton rows={2} />
      ) : away.isError ? (
        <ErrorState error={away.error} onRetry={() => void away.refetch()} />
      ) : away.data.length === 0 ? (
        <p className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
          Nobody is away in these dates.
        </p>
      ) : (
        <ul className="divide-y rounded-lg border text-sm">
          {away.data.map((a) => (
            <li
              key={`${a.employee.id}-${a.type.id}-${a.startDate}`}
              className="flex flex-wrap items-center justify-between gap-2 px-3 py-2"
            >
              <Link
                to={`/app/hrms/employees/${a.employee.id}`}
                className="font-medium underline-offset-4 hover:underline"
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
