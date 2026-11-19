import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { FormError } from '@/components/form/FormError'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { ErrorState, ListSkeleton } from '@/components/states'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table'
import { requiredText } from '@/features/auth/schemas'
import { invalidateHr } from '@/features/hrms/invalidation'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { DepartmentView } from '@/lib/api/types'

const departmentSchema = z.object({
  name: requiredText(80),
  description: z.string().trim().max(500, 'Use at most 500 characters.'),
})
type DepartmentValues = z.infer<typeof departmentSchema>
const DEPARTMENT_FIELDS = ['name', 'description'] as const

/** Settings → HRMS → Departments: the units employees and job roles belong to. */
export function DepartmentsSection() {
  const api = useApi()
  const queryClient = useQueryClient()
  const active = useQuery({
    queryKey: ['hr', 'departments', false],
    queryFn: () => api.get<DepartmentView[]>('/hr/departments'),
  })
  const archived = useQuery({
    queryKey: ['hr', 'departments', true],
    queryFn: () => api.get<DepartmentView[]>('/hr/departments?archived=true'),
  })
  const [editing, setEditing] = useState<DepartmentView | 'new' | null>(null)
  const [archiving, setArchiving] = useState<DepartmentView | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

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
      return false
    } finally {
      setBusy(false)
    }
  }

  return (
    <section aria-label="Departments" className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Departments</h2>
        <Button onClick={() => setEditing('new')}>New department</Button>
      </div>
      <FormError message={archiving ? null : error} />
      {active.isPending ? (
        <ListSkeleton />
      ) : active.isError ? (
        <ErrorState error={active.error} onRetry={() => void active.refetch()} />
      ) : (
        <div className="overflow-x-auto rounded-lg border">
          <Table>
            <TableHeader>
              <TableRow>
                <TableHead>Name</TableHead>
                <TableHead>Description</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {[...active.data, ...(archived.data ?? [])].map((d) => (
                <TableRow key={d.id}>
                  <TableCell>
                    {d.name} {d.archivedAt && <Badge variant="outline">Archived</Badge>}
                  </TableCell>
                  <TableCell className="max-w-md whitespace-normal">
                    {d.description ?? '—'}
                  </TableCell>
                  <TableCell className="space-x-2 text-right">
                    {d.archivedAt ? (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        aria-label={`Restore ${d.name}`}
                        onClick={() =>
                          void act(
                            () => api.post(`/hr/departments/${d.id}/restore`),
                            `${d.name} restored.`,
                          )
                        }
                      >
                        Restore
                      </Button>
                    ) : (
                      <>
                        <Button
                          size="sm"
                          variant="outline"
                          aria-label={`Edit ${d.name}`}
                          onClick={() => setEditing(d)}
                        >
                          Edit
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          aria-label={`Archive ${d.name}`}
                          onClick={() => {
                            setError(null)
                            setArchiving(d)
                          }}
                        >
                          Archive
                        </Button>
                      </>
                    )}
                  </TableCell>
                </TableRow>
              ))}
            </TableBody>
          </Table>
        </div>
      )}
      {editing && (
        <DepartmentDialog
          department={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
        />
      )}
      <ConfirmDialog
        open={archiving !== null}
        title={`Archive ${archiving?.name ?? ''}?`}
        description="Departments with employees in them can't be archived."
        confirmLabel="Archive"
        busy={busy}
        error={error}
        onCancel={() => {
          setError(null)
          setArchiving(null)
        }}
        onConfirm={() => {
          if (!archiving) return
          void act(
            () => api.post(`/hr/departments/${archiving.id}/archive`),
            `${archiving.name} archived.`,
          ).then((ok) => ok && setArchiving(null))
        }}
      />
    </section>
  )
}

function DepartmentDialog({
  department,
  onClose,
}: {
  department?: DepartmentView
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<DepartmentValues>({
    resolver: zodResolver(departmentSchema),
    defaultValues: { name: department?.name ?? '', description: department?.description ?? '' },
  })
  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      name: values.name.trim(),
      description: values.description.trim() || null,
      ...(department ? { version: department.version } : {}),
    }
    try {
      if (department) await api.put(`/hr/departments/${department.id}`, body)
      else await api.post('/hr/departments', body)
      await invalidateHr(queryClient)
      toast.success(department ? 'Changes saved.' : `${body.name} created.`)
      onClose()
    } catch (error) {
      if (department && error instanceof ApiError && error.status === 409)
        void invalidateHr(queryClient)
      if (!applyFieldErrors(error, form.setError, DEPARTMENT_FIELDS))
        setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{department ? `Edit ${department.name}` : 'New department'}</DialogTitle>
          <DialogDescription>
            Employees and job roles can be grouped by department.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <TextField form={form} name="name" label="Name" maxLength={80} />
          <TextAreaField form={form} name="description" label="Description" maxLength={500} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {department ? 'Save changes' : 'Create department'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
