import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { Checkbox } from '@/components/form/Checkbox'
import { FormError } from '@/components/form/FormError'
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
import { formatDays } from '@/features/hrms/labels'
import { invalidateHr } from '@/features/hrms/invalidation'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { LeaveTypeView } from '@/lib/api/types'

const leaveTypeSchema = z.object({
  name: requiredText(60),
  paid: z.boolean(),
  annualAllowance: z
    .string()
    .trim()
    .refine(
      (v) => v === '' || (/^\d+$/.test(v) && Number(v) <= 366),
      'Enter a whole number of days from 0 to 366, or leave it blank for unlimited.',
    ),
})
type LeaveTypeValues = z.infer<typeof leaveTypeSchema>
const LEAVE_TYPE_FIELDS = ['name', 'paid', 'annualAllowance'] as const

/** Settings → HRMS → Leave types: what people can take, and how many days a year (blank = unlimited). */
export function LeaveTypesSection() {
  const api = useApi()
  const queryClient = useQueryClient()
  const active = useQuery({
    queryKey: ['hr', 'leave-types', false],
    queryFn: () => api.get<LeaveTypeView[]>('/hr/leave-types'),
  })
  const archived = useQuery({
    queryKey: ['hr', 'leave-types', true],
    queryFn: () => api.get<LeaveTypeView[]>('/hr/leave-types?archived=true'),
  })
  const [editing, setEditing] = useState<LeaveTypeView | 'new' | null>(null)
  const [archiving, setArchiving] = useState<LeaveTypeView | null>(null)
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
    <section aria-label="Leave types" className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Leave types</h2>
        <Button onClick={() => setEditing('new')}>New leave type</Button>
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
                <TableHead>Paid</TableHead>
                <TableHead>Allowance</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {[...active.data, ...(archived.data ?? [])].map((t) => (
                <TableRow key={t.id}>
                  <TableCell>
                    {t.name} {t.archivedAt && <Badge variant="outline">Archived</Badge>}
                  </TableCell>
                  <TableCell>{t.paid ? 'Paid' : 'Unpaid'}</TableCell>
                  <TableCell>{formatDays(t.annualAllowance)}</TableCell>
                  <TableCell className="space-x-2 text-right">
                    {t.archivedAt ? (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        aria-label={`Restore ${t.name}`}
                        onClick={() =>
                          void act(
                            () => api.post(`/hr/leave-types/${t.id}/restore`),
                            `${t.name} restored.`,
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
                          aria-label={`Edit ${t.name}`}
                          onClick={() => setEditing(t)}
                        >
                          Edit
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          aria-label={`Archive ${t.name}`}
                          onClick={() => {
                            setError(null)
                            setArchiving(t)
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
        <LeaveTypeDialog
          leaveType={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
        />
      )}
      <ConfirmDialog
        open={archiving !== null}
        title={`Archive ${archiving?.name ?? ''}?`}
        description="New requests can't use it. Existing requests and balances keep it."
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
            () => api.post(`/hr/leave-types/${archiving.id}/archive`),
            `${archiving.name} archived.`,
          ).then((ok) => ok && setArchiving(null))
        }}
      />
    </section>
  )
}

function LeaveTypeDialog({
  leaveType,
  onClose,
}: {
  leaveType?: LeaveTypeView
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<LeaveTypeValues>({
    resolver: zodResolver(leaveTypeSchema),
    defaultValues: {
      name: leaveType?.name ?? '',
      paid: leaveType?.paid ?? false,
      annualAllowance: leaveType?.annualAllowance == null ? '' : String(leaveType.annualAllowance),
    },
  })
  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      name: values.name.trim(),
      paid: values.paid,
      annualAllowance: values.annualAllowance.trim() === '' ? null : Number(values.annualAllowance),
      ...(leaveType ? { version: leaveType.version } : {}),
    }
    try {
      if (leaveType) await api.put(`/hr/leave-types/${leaveType.id}`, body)
      else await api.post('/hr/leave-types', body)
      await invalidateHr(queryClient)
      toast.success(leaveType ? 'Changes saved.' : `${body.name} created.`)
      onClose()
    } catch (error) {
      if (leaveType && error instanceof ApiError && error.status === 409)
        void invalidateHr(queryClient)
      if (!applyFieldErrors(error, form.setError, LEAVE_TYPE_FIELDS))
        setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{leaveType ? `Edit ${leaveType.name}` : 'New leave type'}</DialogTitle>
          <DialogDescription>
            Leave without an annual allowance is unlimited and has no balance.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <TextField form={form} name="name" label="Name" maxLength={60} />
          <label className="flex items-center gap-2 text-sm">
            <Checkbox {...form.register('paid')} />
            Paid
          </label>
          <TextField
            form={form}
            name="annualAllowance"
            label="Annual allowance (days)"
            inputMode="numeric"
            hint="Leave blank for unlimited."
          />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {leaveType ? 'Save changes' : 'Create leave type'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
