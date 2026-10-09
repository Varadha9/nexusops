import { zodResolver } from '@hookform/resolvers/zod'
import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { FormError } from '@/components/form/FormError'
import { TextAreaField } from '@/components/form/TextAreaField'
import { TextField } from '@/components/form/TextField'
import { Button } from '@/components/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog'
import { requiredText } from '@/features/auth/schemas'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { PolicyView } from '@/lib/api/types'
import { invalidateHr } from './invalidation'

const schema = z.object({
  title: requiredText(200),
  body: requiredText(50000),
})
type Values = z.infer<typeof schema>
const FIELDS = ['title', 'body'] as const

/** Write a new draft, or edit a policy. Editing a published policy keeps it published. */
export function PolicyFormDialog({
  policy,
  onClose,
  onSaved,
}: {
  policy?: PolicyView
  onClose: () => void
  onSaved: (saved: PolicyView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: { title: policy?.title ?? '', body: policy?.body ?? '' },
  })
  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      title: values.title.trim(),
      body: values.body.trim(),
      ...(policy ? { version: policy.version } : {}),
    }
    try {
      const saved = policy
        ? await api.put<PolicyView>(`/hr/policies/${policy.id}`, body)
        : await api.post<PolicyView>('/hr/policies', body)
      queryClient.setQueryData(['hr', 'policy', saved.id], saved)
      await invalidateHr(queryClient)
      toast.success(policy ? 'Changes saved.' : 'Draft saved.')
      onSaved(saved)
    } catch (error) {
      if (policy && error instanceof ApiError && error.status === 409)
        void queryClient.invalidateQueries({ queryKey: ['hr', 'policy', policy.id] })
      if (!applyFieldErrors(error, form.setError, FIELDS)) setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{policy ? `Edit ${policy.title}` : 'New policy'}</DialogTitle>
          <DialogDescription>Plain text. Write it so anyone can follow it.</DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <TextField form={form} name="title" label="Title" maxLength={200} />
          <TextAreaField form={form} name="body" label="Policy" rows={14} maxLength={50000} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {policy ? 'Save changes' : 'Save draft'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
