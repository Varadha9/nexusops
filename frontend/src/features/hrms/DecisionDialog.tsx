import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
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
import { Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { LeaveRequestView } from '@/lib/api/types'
import { formatDate } from '@/lib/format'
import { invalidateHr } from './invalidation'
import { formatDays } from './labels'

/** Approve or reject a pending request, with an optional note for the person asking. */
export function DecisionDialog({
  request,
  outcome,
  onClose,
}: {
  request: LeaveRequestView
  outcome: 'approve' | 'reject'
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [note, setNote] = useState('')
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const verb = outcome === 'approve' ? 'Approve' : 'Reject'

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setFormError(null)
    setBusy(true)
    try {
      await api.post<LeaveRequestView>(`/hr/leave-requests/${request.id}/${outcome}`, {
        note: note.trim() || null,
        version: request.version,
      })
      await invalidateHr(queryClient)
      toast.success(outcome === 'approve' ? 'Leave approved.' : 'Leave rejected.')
      onClose()
    } catch (error) {
      setFormError(problemMessage(error))
      // the request may have changed under us: refresh the lists
      void invalidateHr(queryClient)
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>
            {verb} leave for {request.employee.name}
          </DialogTitle>
          <DialogDescription>
            {formatDate(request.startDate)} – {formatDate(request.endDate)} ·{' '}
            {formatDays(request.days)} · {request.type.name}
            {request.reason ? ` · ${request.reason}` : ''}
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void submit(e)} className="space-y-4">
          <Field id="field-note" label="Note">
            <Textarea
              id="field-note"
              rows={3}
              maxLength={500}
              value={note}
              onChange={(e) => setNote(e.target.value)}
            />
          </Field>
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button
              type="submit"
              variant={outcome === 'reject' ? 'destructive' : 'default'}
              disabled={busy}
            >
              {verb}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
