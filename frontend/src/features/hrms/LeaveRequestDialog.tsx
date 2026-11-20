import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
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
import { useApi } from '@/lib/api/ApiContext'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { EmployeeRef, LeaveRequestView, LeaveTypeView } from '@/lib/api/types'
import { EmployeePicker } from './EmployeePicker'
import { invalidateHr } from './invalidation'

const schema = z
  .object({
    employeeId: z.string(),
    typeId: z.string().min(1, 'Choose a leave type.'),
    startDate: z.string().min(1, 'Enter the first day.'),
    endDate: z.string().min(1, 'Enter the last day.'),
    reason: z.string().trim().max(500, 'Use at most 500 characters.'),
  })
  .superRefine((v, ctx) => {
    if (v.startDate && v.endDate && v.endDate < v.startDate)
      ctx.addIssue({
        code: 'custom',
        path: ['endDate'],
        message: "The end date can't be before the start date.",
      })
  })
type Values = z.infer<typeof schema>
const FIELDS = ['employeeId', 'typeId', 'startDate', 'endDate', 'reason'] as const

/**
 * Asks for leave: for the given employee, for someone chosen in the dialog, or — with `self` — for the signed-in
 * employee through the self-service endpoint (no employee field).
 */
export function LeaveRequestDialog({
  employee,
  self = false,
  onClose,
  onSaved,
}: {
  employee?: EmployeeRef
  self?: boolean
  onClose: () => void
  onSaved: (saved: LeaveRequestView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const needsEmployee = !self && !employee
  const types = useQuery({
    queryKey: ['hr', 'leave-types', false],
    queryFn: () => api.get<LeaveTypeView[]>('/hr/leave-types'),
  })
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      employeeId: employee?.id ?? '',
      typeId: '',
      startDate: '',
      endDate: '',
      reason: '',
    },
  })
  const errors = form.formState.errors
  const revalidate = { shouldValidate: form.formState.isSubmitted }

  const onSubmit = form.handleSubmit(async (v) => {
    setFormError(null)
    if (needsEmployee && !v.employeeId) {
      form.setError('employeeId', { type: 'validate', message: 'Choose an employee.' })
      return
    }
    const body = {
      ...(self ? {} : { employeeId: v.employeeId }),
      typeId: v.typeId,
      startDate: v.startDate,
      endDate: v.endDate,
      reason: v.reason.trim() || null,
    }
    try {
      const saved = await api.post<LeaveRequestView>(
        self ? '/hr/me/leave-requests' : '/hr/leave-requests',
        body,
      )
      await invalidateHr(queryClient)
      toast.success(`Leave requested: ${saved.days} working ${saved.days === 1 ? 'day' : 'days'}.`)
      onSaved(saved)
    } catch (error) {
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Request leave</DialogTitle>
          <DialogDescription>
            Working days are counted for you: weekends and public holidays don't count.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          {needsEmployee && (
            <EmployeePicker
              id="field-employeeId"
              label="Employee"
              value={form.watch('employeeId')}
              error={errors.employeeId?.message}
              blankLabel="Choose…"
              onChange={(id) => form.setValue('employeeId', id, revalidate)}
            />
          )}
          <Field id="field-typeId" label="Leave type" error={errors.typeId?.message}>
            <NativeSelect
              id="field-typeId"
              aria-invalid={errors.typeId ? true : undefined}
              aria-describedby={describedBy('field-typeId', errors.typeId?.message)}
              {...form.register('typeId')}
            >
              <option value="">Choose…</option>
              {(types.data ?? []).map((t) => (
                <option key={t.id} value={t.id}>
                  {t.name}
                  {t.paid ? '' : ' (unpaid)'}
                </option>
              ))}
            </NativeSelect>
          </Field>
          <div className="grid gap-3 sm:grid-cols-2">
            <Field id="field-startDate" label="From" error={errors.startDate?.message}>
              <Input
                id="field-startDate"
                type="date"
                aria-invalid={errors.startDate ? true : undefined}
                aria-describedby={describedBy('field-startDate', errors.startDate?.message)}
                {...form.register('startDate')}
              />
            </Field>
            <Field id="field-endDate" label="To" error={errors.endDate?.message}>
              <Input
                id="field-endDate"
                type="date"
                aria-invalid={errors.endDate ? true : undefined}
                aria-describedby={describedBy('field-endDate', errors.endDate?.message)}
                {...form.register('endDate')}
              />
            </Field>
          </div>
          <Field id="field-reason" label="Reason" error={errors.reason?.message}>
            <Textarea
              id="field-reason"
              rows={3}
              maxLength={500}
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
              Request leave
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
