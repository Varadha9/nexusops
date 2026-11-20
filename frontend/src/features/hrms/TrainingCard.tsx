import { useQuery } from '@tanstack/react-query'
import { useState } from 'react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { ErrorState, ListSkeleton } from '@/components/states'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { EmployeeView, Page, TrainingAssignmentView } from '@/lib/api/types'
import { formatDate } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { AssignTrainingDialog } from './AssignTrainingDialog'

/** The employee's open training, and — for training managers — a way to assign more. */
export function TrainingCard({ employee }: { employee: EmployeeView }) {
  const api = useApi()
  const can = useCan()
  const [assigning, setAssigning] = useState(false)
  const open = useQuery({
    queryKey: ['hr', 'training', { employeeId: employee.id, status: 'ASSIGNED' }],
    queryFn: () =>
      api.get<Page<TrainingAssignmentView>>(
        `/hr/training-assignments?${toQuery({ employeeId: employee.id, status: 'ASSIGNED', size: 20 })}`,
      ),
  })
  const ref = {
    id: employee.id,
    name: employee.person.name,
    employeeNumber: employee.employeeNumber,
  }
  return (
    <Card role="region" aria-label="Training">
      <CardHeader className="flex items-center justify-between">
        <CardTitle>Training</CardTitle>
        {can(PERMISSIONS.hrTraining) && (
          <Button size="sm" variant="outline" onClick={() => setAssigning(true)}>
            Assign training
          </Button>
        )}
      </CardHeader>
      <CardContent className="text-sm">
        {open.isPending ? (
          <ListSkeleton rows={2} />
        ) : open.isError ? (
          <ErrorState error={open.error} onRetry={() => void open.refetch()} />
        ) : open.data.items.length === 0 ? (
          <p className="text-muted-foreground">No open training.</p>
        ) : (
          <ul className="divide-y rounded-lg border">
            {open.data.items.map((a) => (
              <li
                key={a.id}
                className="flex flex-wrap items-center justify-between gap-2 px-3 py-2"
              >
                <span className="font-medium">{a.course.title}</span>
                <span className="flex items-center gap-2 text-muted-foreground">
                  {a.dueOn ? `Due ${formatDate(a.dueOn)}` : 'No due date'}
                  {a.overdue && <Badge variant="destructive">Overdue</Badge>}
                </span>
              </li>
            ))}
          </ul>
        )}
      </CardContent>
      {assigning && <AssignTrainingDialog employee={ref} onClose={() => setAssigning(false)} />}
    </Card>
  )
}
