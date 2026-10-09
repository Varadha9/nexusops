import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
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
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { EmployeeView } from '@/lib/api/types'
import { employeeKey } from './EmployeeFormDialog'
import { invalidateHr } from './invalidation'

/** Ends someone's employment on a last day (D2). Leave and training after that day are cancelled by the server. */
export function EndEmploymentDialog({
  employee,
  onClose,
}: {
  employee: EmployeeView
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [endDate, setEndDate] = useState('')
  const [reason, setReason] = useState('')
  const [dateError, setDateError] = useState<string | undefined>()
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setFormError(null)
    if (!endDate) {
      setDateError('Enter the last day.')
      return
    }
    setDateError(undefined)
    setBusy(true)
    try {
      const saved = await api.post<EmployeeView>(`/hr/employees/${employee.id}/end`, {
        endDate,
        reason: reason.trim() || null,
        version: employee.version,
      })
      queryClient.setQueryData(employeeKey(saved.id), saved)
      await invalidateHr(queryClient)
      toast.success('Employment ended.')
      onClose()
    } catch (error) {
      setFormError(problemMessage(error))
      void queryClient.invalidateQueries({ queryKey: employeeKey(employee.id) })
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>End employment of {employee.person.name}</DialogTitle>
          <DialogDescription>
            Leave and training after the last day are cancelled. Their tasks, documents and history
            stay.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void submit(e)} className="space-y-4">
          <Field id="field-endDate" label="Last day" error={dateError}>
            <Input
              id="field-endDate"
              type="date"
              value={endDate}
              aria-invalid={dateError ? true : undefined}
              aria-describedby={describedBy('field-endDate', dateError)}
              onChange={(e) => setEndDate(e.target.value)}
            />
          </Field>
          <Field id="field-endReason" label="Reason">
            <Textarea
              id="field-endReason"
              rows={3}
              maxLength={500}
              value={reason}
              onChange={(e) => setReason(e.target.value)}
            />
          </Field>
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={busy}>
              End employment
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
