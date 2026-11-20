import { keepPreviousData, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useSearchParams } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Checkbox } from '@/components/form/Checkbox'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Label } from '@/components/ui/label'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { CourseView, Page, TrainingAssignmentView, TrainingStatus } from '@/lib/api/types'
import { formatDate } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { AssignTrainingDialog } from './AssignTrainingDialog'
import { CourseFormDialog } from './CourseFormDialog'
import { invalidateHr } from './invalidation'
import { TRAINING_STATUS_LABELS } from './labels'

const SIZE = 20

export function TrainingPage() {
  const can = useCan()
  const canManage = can(PERMISSIONS.hrTraining)
  const [dialog, setDialog] = useState<'course' | 'assign' | null>(null)
  return (
    <>
      <PageHeader
        title="Training"
        description="Courses that teach the skills your roles need, and who is taking them."
        actions={
          canManage && (
            <>
              <Button variant="outline" onClick={() => setDialog('assign')}>
                Assign training
              </Button>
              <Button onClick={() => setDialog('course')}>New course</Button>
            </>
          )
        }
      />
      <div className="space-y-6">
        <Courses canManage={canManage} />
        <Assignments canManage={canManage} />
      </div>
      {dialog === 'course' && <CourseFormDialog onClose={() => setDialog(null)} />}
      {dialog === 'assign' && <AssignTrainingDialog onClose={() => setDialog(null)} />}
    </>
  )
}

function Courses({ canManage }: { canManage: boolean }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [editing, setEditing] = useState<CourseView | null>(null)
  const [archiving, setArchiving] = useState<CourseView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const courses = useQuery({
    queryKey: ['hr', 'courses', false],
    queryFn: () => api.get<CourseView[]>('/hr/courses'),
  })

  async function archive(course: CourseView) {
    setBusy(true)
    setError(null)
    try {
      await api.post(`/hr/courses/${course.id}/archive`)
      await invalidateHr(queryClient)
      toast.success(`${course.title} archived.`)
      setArchiving(null)
    } catch (e) {
      setError(problemMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-label="Courses" className="space-y-3">
      <h2 className="text-base font-semibold">Courses</h2>
      {courses.isPending ? (
        <ListSkeleton rows={3} />
      ) : courses.isError ? (
        <ErrorState error={courses.error} onRetry={() => void courses.refetch()} />
      ) : courses.data.length === 0 ? (
        <p className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
          No courses yet.
        </p>
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Title</TableHead>
                <TableHead>Skills taught</TableHead>
                <TableHead>Duration</TableHead>
                {canManage && (
                  <TableHead>
                    <span className="sr-only">Actions</span>
                  </TableHead>
                )}
              </TableRow>
            </TableHeader>
            <TableBody>
              {courses.data.map((c) => (
                <TableRow key={c.id}>
                  <TableCell className="font-medium">
                    {c.link ? (
                      <a
                        href={c.link}
                        target="_blank"
                        rel="noreferrer"
                        className="underline-offset-4 hover:underline"
                      >
                        {c.title}
                      </a>
                    ) : (
                      c.title
                    )}
                  </TableCell>
                  <TableCell className="max-w-md whitespace-normal">
                    {c.skills.length === 0
                      ? '—'
                      : c.skills.map((s) => `${s.name} (${s.level})`).join(', ')}
                  </TableCell>
                  <TableCell>{c.durationHours != null ? `${c.durationHours} h` : '—'}</TableCell>
                  {canManage && (
                    <TableCell className="space-x-2 whitespace-nowrap text-right">
                      <Button
                        size="sm"
                        variant="outline"
                        aria-label={`Edit ${c.title}`}
                        onClick={() => setEditing(c)}
                      >
                        Edit
                      </Button>
                      <Button
                        size="sm"
                        variant="outline"
                        aria-label={`Archive ${c.title}`}
                        onClick={() => {
                          setError(null)
                          setArchiving(c)
                        }}
                      >
                        Archive
                      </Button>
                    </TableCell>
                  )}
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {editing && <CourseFormDialog course={editing} onClose={() => setEditing(null)} />}
      <ConfirmDialog
        open={archiving !== null}
        title={`Archive ${archiving?.title ?? ''}?`}
        description="It can't be assigned any more. Training already assigned stays."
        confirmLabel="Archive course"
        busy={busy}
        error={error}
        onCancel={() => setArchiving(null)}
        onConfirm={() => archiving && void archive(archiving)}
      />
    </section>
  )
}

function Assignments({ canManage }: { canManage: boolean }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [params, setParams] = useSearchParams()
  const status = params.get('status') ?? ''
  const overdue = params.get('overdue') === 'true'
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [cancelling, setCancelling] = useState<TrainingAssignmentView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const assignments = useQuery({
    queryKey: ['hr', 'training', { status, overdue, page }],
    queryFn: () =>
      api.get<Page<TrainingAssignmentView>>(
        `/hr/training-assignments?${toQuery({
          status,
          overdue: overdue ? 'true' : '',
          page,
          size: SIZE,
        })}`,
      ),
    placeholderData: keepPreviousData,
  })

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params)
    for (const [key, value] of Object.entries(next)) {
      if (value) merged.set(key, value)
      else merged.delete(key)
    }
    setParams(merged, { replace: true })
  }

  async function act(action: () => Promise<unknown>, success: string) {
    setBusy(true)
    setError(null)
    try {
      await action()
      await invalidateHr(queryClient)
      toast.success(success)
      return true
    } catch (e) {
      setError(problemMessage(e))
      void invalidateHr(queryClient)
      return false
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-label="Assignments" className="space-y-3">
      <h2 className="text-base font-semibold">Assignments</h2>
      <div className="flex flex-wrap items-end gap-3">
        <div className="space-y-1.5">
          <Label htmlFor="training-filter-status">Status</Label>
          <NativeSelect
            id="training-filter-status"
            value={status}
            onChange={(e) => update({ status: e.target.value, page: '' })}
          >
            <option value="">Any status</option>
            {(Object.keys(TRAINING_STATUS_LABELS) as TrainingStatus[]).map((s) => (
              <option key={s} value={s}>
                {TRAINING_STATUS_LABELS[s]}
              </option>
            ))}
          </NativeSelect>
        </div>
        <div className="flex items-center gap-2 pb-2">
          <Checkbox
            id="training-filter-overdue"
            checked={overdue}
            onChange={(e) => update({ overdue: e.target.checked ? 'true' : '', page: '' })}
          />
          <Label htmlFor="training-filter-overdue">Overdue only</Label>
        </div>
      </div>
      <FormError message={cancelling ? null : error} />
      {assignments.isPending ? (
        <ListSkeleton />
      ) : assignments.isError ? (
        <ErrorState error={assignments.error} onRetry={() => void assignments.refetch()} />
      ) : assignments.data.items.length === 0 ? (
        <p className="rounded-lg border border-dashed p-6 text-center text-sm text-muted-foreground">
          No training assignments match.
        </p>
      ) : (
        <>
          <div className="overflow-x-auto rounded-lg border">
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Employee</TableHead>
                  <TableHead>Course</TableHead>
                  <TableHead>Due</TableHead>
                  <TableHead>Status</TableHead>
                  {canManage && (
                    <TableHead>
                      <span className="sr-only">Actions</span>
                    </TableHead>
                  )}
                </TableRow>
              </TableHeader>
              <TableBody>
                {assignments.data.items.map((a) => (
                  <TableRow key={a.id}>
                    <TableCell>
                      <Link
                        to={`/app/hrms/employees/${a.employee.id}`}
                        className="font-medium underline-offset-4 hover:underline"
                      >
                        {a.employee.name}
                      </Link>
                    </TableCell>
                    <TableCell>{a.course.title}</TableCell>
                    <TableCell className="space-x-2 whitespace-nowrap">
                      <span>{formatDate(a.dueOn)}</span>
                      {a.overdue && <Badge variant="destructive">Overdue</Badge>}
                    </TableCell>
                    <TableCell>
                      <Badge variant={a.status === 'ASSIGNED' ? 'secondary' : 'outline'}>
                        {TRAINING_STATUS_LABELS[a.status]}
                      </Badge>
                    </TableCell>
                    {canManage && (
                      <TableCell className="space-x-2 whitespace-nowrap text-right">
                        {a.status === 'ASSIGNED' && (
                          <>
                            <Button
                              size="sm"
                              disabled={busy}
                              aria-label={`Mark complete ${a.course.title} for ${a.employee.name}`}
                              onClick={() =>
                                void act(
                                  () =>
                                    api.post(`/hr/training-assignments/${a.id}/complete`, {
                                      note: null,
                                      version: a.version,
                                    }),
                                  'Training completed.',
                                )
                              }
                            >
                              Mark complete
                            </Button>
                            <Button
                              size="sm"
                              variant="outline"
                              aria-label={`Cancel ${a.course.title} for ${a.employee.name}`}
                              onClick={() => {
                                setError(null)
                                setCancelling(a)
                              }}
                            >
                              Cancel
                            </Button>
                          </>
                        )}
                      </TableCell>
                    )}
                  </TableRow>
                ))}
              </TableBody>
            </Table>
          </div>
          <Pagination
            page={assignments.data.page}
            size={assignments.data.size}
            total={assignments.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}
      <ConfirmDialog
        open={cancelling !== null}
        title="Cancel this training?"
        description={
          cancelling ? `${cancelling.course.title} for ${cancelling.employee.name}.` : ''
        }
        confirmLabel="Cancel training"
        busy={busy}
        error={error}
        onCancel={() => setCancelling(null)}
        onConfirm={() =>
          cancelling &&
          void act(
            () =>
              api.post(`/hr/training-assignments/${cancelling.id}/cancel`, {
                version: cancelling.version,
              }),
            'Training cancelled.',
          ).then((ok) => ok && setCancelling(null))
        }
      />
    </section>
  )
}
