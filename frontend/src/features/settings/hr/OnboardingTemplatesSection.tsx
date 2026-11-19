import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useFieldArray, useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
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
import { Input } from '@/components/ui/input'
import { Textarea } from '@/components/ui/textarea'
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
import { ONBOARDING_OWNER_LABELS } from '@/features/hrms/labels'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { OnboardingOwner, OnboardingTemplateView } from '@/lib/api/types'

const OWNERS = Object.keys(ONBOARDING_OWNER_LABELS) as OnboardingOwner[]

const templateSchema = z.object({
  name: requiredText(80),
  items: z
    .array(
      z.object({
        title: requiredText(200),
        description: z.string().trim().max(2000, 'Use at most 2000 characters.'),
        owner: z.enum(['HR', 'MANAGER', 'EMPLOYEE']),
        dueOffsetDays: z
          .string()
          .trim()
          .refine(
            (v) => /^\d+$/.test(v) && Number(v) <= 365,
            'Enter a whole number of days from 0 to 365.',
          ),
      }),
    )
    .min(1, 'Add at least one item.'),
})
type TemplateValues = z.infer<typeof templateSchema>
const TEMPLATE_FIELDS = ['name'] as const

/** Settings → HRMS → Onboarding templates: checklists a new hire works through, edited as a whole. */
export function OnboardingTemplatesSection() {
  const api = useApi()
  const queryClient = useQueryClient()
  const active = useQuery({
    queryKey: ['hr', 'onboarding-templates', false],
    queryFn: () => api.get<OnboardingTemplateView[]>('/hr/onboarding-templates'),
  })
  const archived = useQuery({
    queryKey: ['hr', 'onboarding-templates', true],
    queryFn: () => api.get<OnboardingTemplateView[]>('/hr/onboarding-templates?archived=true'),
  })
  const [editing, setEditing] = useState<OnboardingTemplateView | 'new' | null>(null)
  const [archiving, setArchiving] = useState<OnboardingTemplateView | null>(null)
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
    <section aria-label="Onboarding templates" className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Onboarding templates</h2>
        <Button onClick={() => setEditing('new')}>New template</Button>
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
                <TableHead>Items</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {[...active.data, ...(archived.data ?? [])].map((t) => (
                <TableRow key={t.id}>
                  <TableCell>
                    {t.name} {t.archivedAt && <Badge variant="outline">Archived</Badge>}
                  </TableCell>
                  <TableCell>{t.items.length}</TableCell>
                  <TableCell className="space-x-2 text-right">
                    {t.archivedAt ? (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        aria-label={`Restore ${t.name}`}
                        onClick={() =>
                          void act(
                            () => api.post(`/hr/onboarding-templates/${t.id}/restore`),
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
        <TemplateDialog
          template={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
        />
      )}
      <ConfirmDialog
        open={archiving !== null}
        title={`Archive ${archiving?.name ?? ''}?`}
        description="It can't be started for new hires any more. Checklists already started keep it."
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
            () => api.post(`/hr/onboarding-templates/${archiving.id}/archive`),
            `${archiving.name} archived.`,
          ).then((ok) => ok && setArchiving(null))
        }}
      />
    </section>
  )
}

function TemplateDialog({
  template,
  onClose,
}: {
  template?: OnboardingTemplateView
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<TemplateValues>({
    resolver: zodResolver(templateSchema),
    defaultValues: {
      name: template?.name ?? '',
      items: (template?.items ?? []).map((item) => ({
        title: item.title,
        description: item.description ?? '',
        owner: item.owner,
        dueOffsetDays: String(item.dueOffsetDays),
      })),
    },
  })
  const items = useFieldArray({ control: form.control, name: 'items' })
  const itemsError = form.formState.errors.items
  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      name: values.name.trim(),
      items: values.items.map((item) => ({
        title: item.title.trim(),
        description: item.description.trim() || null,
        owner: item.owner,
        dueOffsetDays: Number(item.dueOffsetDays),
      })),
      ...(template ? { version: template.version } : {}),
    }
    try {
      if (template) await api.put(`/hr/onboarding-templates/${template.id}`, body)
      else await api.post('/hr/onboarding-templates', body)
      await invalidateHr(queryClient)
      toast.success(template ? 'Changes saved.' : `${body.name} created.`)
      onClose()
    } catch (error) {
      if (template && error instanceof ApiError && error.status === 409)
        void invalidateHr(queryClient)
      if (!applyFieldErrors(error, form.setError, TEMPLATE_FIELDS))
        setFormError(problemMessage(error))
    }
  })

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{template ? `Edit ${template.name}` : 'New template'}</DialogTitle>
          <DialogDescription>
            Each item is due a number of days after the person&apos;s start date.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <TextField form={form} name="name" label="Name" maxLength={80} />
          <fieldset className="space-y-4">
            <legend className="text-sm font-medium">Items</legend>
            {items.fields.map((row, i) => {
              const n = i + 1
              const errors = itemsError?.[i]
              const titleId = `field-items-${i}-title`
              const descriptionId = `field-items-${i}-description`
              const ownerId = `field-items-${i}-owner`
              const dueId = `field-items-${i}-dueOffsetDays`
              return (
                <div key={row.id} className="space-y-3 rounded-lg border p-3">
                  <Field id={titleId} label={`Item ${n} title`} error={errors?.title?.message}>
                    <Input
                      id={titleId}
                      maxLength={200}
                      aria-invalid={errors?.title ? true : undefined}
                      aria-describedby={describedBy(titleId, errors?.title?.message)}
                      {...form.register(`items.${i}.title`)}
                    />
                  </Field>
                  <Field
                    id={descriptionId}
                    label={`Item ${n} description`}
                    error={errors?.description?.message}
                  >
                    <Textarea
                      id={descriptionId}
                      rows={2}
                      maxLength={2000}
                      aria-invalid={errors?.description ? true : undefined}
                      aria-describedby={describedBy(descriptionId, errors?.description?.message)}
                      {...form.register(`items.${i}.description`)}
                    />
                  </Field>
                  <div className="grid items-start gap-3 sm:grid-cols-[1fr_1fr_auto]">
                    <Field id={ownerId} label={`Item ${n} owner`} error={errors?.owner?.message}>
                      <NativeSelect id={ownerId} {...form.register(`items.${i}.owner`)}>
                        {OWNERS.map((owner) => (
                          <option key={owner} value={owner}>
                            {ONBOARDING_OWNER_LABELS[owner]}
                          </option>
                        ))}
                      </NativeSelect>
                    </Field>
                    <Field
                      id={dueId}
                      label={`Item ${n} due (days after start)`}
                      error={errors?.dueOffsetDays?.message}
                    >
                      <Input
                        id={dueId}
                        type="number"
                        min={0}
                        max={365}
                        inputMode="numeric"
                        aria-invalid={errors?.dueOffsetDays ? true : undefined}
                        aria-describedby={describedBy(dueId, errors?.dueOffsetDays?.message)}
                        {...form.register(`items.${i}.dueOffsetDays`)}
                      />
                    </Field>
                    <Button
                      type="button"
                      variant="outline"
                      size="sm"
                      className="sm:mt-6"
                      aria-label={`Remove item ${n}`}
                      onClick={() => items.remove(i)}
                    >
                      Remove
                    </Button>
                  </div>
                </div>
              )
            })}
            {(itemsError?.root?.message ?? itemsError?.message) && (
              <p role="alert" className="text-xs text-destructive">
                {itemsError?.root?.message ?? itemsError?.message}
              </p>
            )}
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() =>
                items.append({ title: '', description: '', owner: 'HR', dueOffsetDays: '0' })
              }
            >
              Add item
            </Button>
          </fieldset>
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {template ? 'Save changes' : 'Create template'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
