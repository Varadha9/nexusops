import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Textarea } from '@/components/ui/textarea'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { TextField } from '@/components/form/TextField'
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { EmployeeView, LeaveTypeView } from '@/lib/api/types'
import { invalidateHr } from './invalidation'

const DAYS_MESSAGE = 'Use a non-zero number of days in steps of 0.5.'

const schema = z.object({
  typeId: z.string().min(1, 'Choose a leave type.'),
  days: z.string().refine((raw) => {
    const text = raw.trim()
    if (!/^[-+]?\d+(\.\d+)?$/.test(text)) return false
    const n = Number(text)
    return n !== 0 && n >= -366 && n <= 366 && Number.isInteger(n * 2)
  }, DAYS_MESSAGE),
  reason: z.string().trim().min(1, 'Required.').max(200, 'Use at most 200 characters.'),
})
type Values = z.infer<typeof schema>
const FIELDS = ['typeId', 'days', 'reason'] as const

/** Adds or takes away days for one leave type and year; the reason is kept in the audit trail. */
export function AdjustBalanceDialog({
  employee,
  year,
  onClose,
}: {
  employee: EmployeeView
  year: number
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const types = useQuery({
    queryKey: ['hr', 'leave-types', false],
    queryFn: () => api.get<LeaveTypeView[]>('/hr/leave-types'),
  })
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { typeId: '', days: '', reason: '' },
  })
  const errors = form.formState.errors

  const onSubmit = form.handleSubmit(async (v) => {
    setFormError(null)
    try {
      await api.post(`/hr/employees/${employee.id}/leave-adjustments`, {
        typeId: v.typeId,
        year,
        days: Number(v.days.trim()),
        reason: v.reason.trim(),
      })
      await invalidateHr(queryClient)
      toast.success('Balance adjusted.')
      onClose()
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>Adjust balance for {employee.person.name}</DialogTitle>
          <DialogDescription>
            Changes the {year} allowance, for example for a carry-over or a correction.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <Field id="field-typeId" label="Leave type" error={errors.typeId?.message}>
            <NativeSelect
              id="field-typeId"
              aria-invalid={errors.typeId ? true : undefined}
              aria-describedby={describedBy('field-typeId', errors.typeId?.message)}
              {...form.register('typeId')}
            >
              <option value="">Choose…</option>
              {(types.data ?? [])
                .filter((t) => t.annualAllowance !== null)
                .map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
            </NativeSelect>
          </Field>
          <TextField
            form={form}
            name="days"
            label="Days (use a minus sign to take days away)"
            inputMode="decimal"
          />
          <Field id="field-reason" label="Reason" error={errors.reason?.message}>
            <Textarea
              id="field-reason"
              rows={3}
              maxLength={200}
              aria-invalid={errors.reason ? true : undefined}
              aria-describedby={describedBy('field-reason', errors.reason?.message)}
              {...form.register('reason')}
            />
          </Field>
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              Adjust
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
