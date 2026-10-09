import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { ConfirmDialog } from '@/components/ConfirmDialog'
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
import { invalidateHr } from '@/features/hrms/invalidation'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { SkillView } from '@/lib/api/types'

const skillSchema = z.object({ name: requiredText(60) })
type SkillValues = z.infer<typeof skillSchema>
const SKILL_FIELDS = ['name'] as const

/** Settings → HRMS → Skills: the catalogue that job roles require and people hold. */
export function SkillsSection() {
  const api = useApi()
  const queryClient = useQueryClient()
  const active = useQuery({
    queryKey: ['hr', 'skills', false],
    queryFn: () => api.get<SkillView[]>('/hr/skills'),
  })
  const archived = useQuery({
    queryKey: ['hr', 'skills', true],
    queryFn: () => api.get<SkillView[]>('/hr/skills?archived=true'),
  })
  const [editing, setEditing] = useState<SkillView | 'new' | null>(null)
  const [archiving, setArchiving] = useState<SkillView | null>(null)
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
    <section aria-label="Skills" className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Skills</h2>
        <Button onClick={() => setEditing('new')}>New skill</Button>
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
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {[...active.data, ...(archived.data ?? [])].map((s) => (
                <TableRow key={s.id}>
                  <TableCell>
                    {s.name} {s.archivedAt && <Badge variant="outline">Archived</Badge>}
                  </TableCell>
                  <TableCell className="space-x-2 text-right">
                    {s.archivedAt ? (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        aria-label={`Restore ${s.name}`}
                        onClick={() =>
                          void act(
                            () => api.post(`/hr/skills/${s.id}/restore`),
                            `${s.name} restored.`,
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
                          aria-label={`Edit ${s.name}`}
                          onClick={() => setEditing(s)}
                        >
                          Edit
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          aria-label={`Archive ${s.name}`}
                          onClick={() => {
                            setError(null)
                            setArchiving(s)
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
        <SkillDialog
          skill={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
        />
      )}
      <ConfirmDialog
        open={archiving !== null}
        title={`Archive ${archiving?.name ?? ''}?`}
        description="It can't be added to job roles or people any more. Where it's already used, it stays."
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
            () => api.post(`/hr/skills/${archiving.id}/archive`),
            `${archiving.name} archived.`,
          ).then((ok) => ok && setArchiving(null))
        }}
      />
    </section>
  )
}

function SkillDialog({ skill, onClose }: { skill?: SkillView; onClose: () => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<SkillValues>({
    resolver: zodResolver(skillSchema),
    defaultValues: { name: skill?.name ?? '' },
  })
  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = { name: values.name.trim(), ...(skill ? { version: skill.version } : {}) }
    try {
      if (skill) await api.put(`/hr/skills/${skill.id}`, body)
      else await api.post('/hr/skills', body)
      await invalidateHr(queryClient)
      toast.success(skill ? 'Changes saved.' : `${body.name} created.`)
      onClose()
    } catch (error) {
      if (skill && error instanceof ApiError && error.status === 409) void invalidateHr(queryClient)
      if (!applyFieldErrors(error, form.setError, SKILL_FIELDS)) setFormError(problemMessage(error))
    }
  })
  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent>
        <DialogHeader>
          <DialogTitle>{skill ? `Edit ${skill.name}` : 'New skill'}</DialogTitle>
          <DialogDescription>
            Job roles can require a skill, and people can hold it at a level from 1 to 5.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <TextField form={form} name="name" label="Name" maxLength={60} />
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {skill ? 'Save changes' : 'Create skill'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
