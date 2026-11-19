import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { NativeSelect } from '@/components/form/NativeSelect'
import { ErrorState, ListSkeleton } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { EmployeeView, LeaveBalanceView, LeaveRequestView, Page } from '@/lib/api/types'
import { formatDate } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { AdjustBalanceDialog } from './AdjustBalanceDialog'
import { formatDays, LEAVE_STATUS_LABELS } from './labels'
import { LeaveRequestDialog } from './LeaveRequestDialog'

/** The year's leave balances, and — for approvers — the person's requests and a way to record leave for them. */
export function LeaveCard({ employee }: { employee: EmployeeView }) {
  const api = useApi()
  const can = useCan()
  const thisYear = new Date().getFullYear()
  const [year, setYear] = useState(thisYear)
  const [dialog, setDialog] = useState<'adjust' | 'request' | null>(null)
  const canApprove = can(PERMISSIONS.hrLeaveApprove)
  const balances = useQuery({
    queryKey: ['hr', 'balances', employee.id, year],
    queryFn: () =>
      api.get<LeaveBalanceView[]>(
        `/hr/employees/${employee.id}/leave-balances?${toQuery({ year })}`,
      ),
  })
  const requests = useQuery({
    queryKey: ['hr', 'leave-requests', { employeeId: employee.id }],
    queryFn: () =>
      api.get<Page<LeaveRequestView>>(
        `/hr/leave-requests?${toQuery({ employeeId: employee.id, size: 10 })}`,
      ),
    enabled: canApprove,
  })
  const ref = {
    id: employee.id,
    name: employee.person.name,
    employeeNumber: employee.employeeNumber,
  }

  return (
    <Card role="region" aria-label="Leave" className="lg:col-span-2">
      <CardHeader>
        <CardTitle>Leave</CardTitle>
      </CardHeader>
      <CardContent className="space-y-4 text-sm">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div className="space-y-1.5">
            <Label htmlFor="leave-year">Year</Label>
            <NativeSelect
              id="leave-year"
              className="w-28"
              value={String(year)}
              onChange={(e) => setYear(Number(e.target.value))}
            >
              {[thisYear - 1, thisYear, thisYear + 1].map((y) => (
                <option key={y} value={y}>
                  {y}
                </option>
              ))}
            </NativeSelect>
          </div>
          <div className="flex gap-2">
            {can(PERMISSIONS.hrEmployeeUpdate) && (
              <Button variant="outline" size="sm" onClick={() => setDialog('adjust')}>
                Adjust balance
              </Button>
            )}
            {canApprove && (
              <Button size="sm" onClick={() => setDialog('request')}>
                Request leave
              </Button>
            )}
          </div>
        </div>
        {balances.isPending ? (
          <ListSkeleton rows={3} />
        ) : balances.isError ? (
          <ErrorState error={balances.error} onRetry={() => void balances.refetch()} />
        ) : (
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Type</TableHead>
                  <TableHead>Entitlement</TableHead>
                  <TableHead>Taken</TableHead>
                  <TableHead>Pending</TableHead>
                  <TableHead>Available</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {balances.data.map((b) => (
                  <TableRow key={b.type.id}>
                    <TableCell className="font-medium">{b.type.name}</TableCell>
                    <TableCell>{formatDays(b.entitlement)}</TableCell>
                    <TableCell>{formatDays(b.taken)}</TableCell>
                    <TableCell>{formatDays(b.pending)}</TableCell>
                    <TableCell>{formatDays(b.available)}</TableCell>
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
        )}
        {canApprove &&
          (requests.isPending ? (
            <ListSkeleton rows={2} />
          ) : requests.isError ? (
            <ErrorState error={requests.error} onRetry={() => void requests.refetch()} />
          ) : requests.data.items.length === 0 ? (
            <p className="text-muted-foreground">No leave requests.</p>
          ) : (
            <ul aria-label="Leave requests" className="divide-y rounded-lg border">
              {requests.data.items.map((r) => (
                <li
                  key={r.id}
                  className="flex flex-wrap items-center justify-between gap-2 px-3 py-2"
                >
                  <span>
                    {formatDate(r.startDate)} – {formatDate(r.endDate)} · {r.type.name} ·{' '}
                    {formatDays(r.days)}
                  </span>
                  <Badge variant={r.status === 'PENDING' ? 'secondary' : 'outline'}>
                    {LEAVE_STATUS_LABELS[r.status]}
                  </Badge>
                </li>
              ))}
            </ul>
          ))}
      </CardContent>
      {dialog === 'adjust' && (
        <AdjustBalanceDialog employee={employee} year={year} onClose={() => setDialog(null)} />
      )}
      {dialog === 'request' && (
        <LeaveRequestDialog
          employee={ref}
          onClose={() => setDialog(null)}
          onSaved={() => setDialog(null)}
        />
      )}
    </Card>
  )
}
