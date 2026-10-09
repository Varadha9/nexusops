import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Controller, useFieldArray, useForm, type FieldPath } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
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
import { SkillLevelSelect } from '@/features/hrms/SkillLevelSelect'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type { DepartmentView, JobRoleView, SkillView } from '@/lib/api/types'

const jobRoleSchema = z.object({
  title: requiredText(80),
  departmentId: z.string(),
  description: z.string().trim().max(2000, 'Use at most 2000 characters.'),
  requiredSkills: z
    .array(
      z.object({
        skillId: z.string().min(1, 'Choose a skill.'),
        minimumLevel: z.string().min(1, 'Choose a level.'),
      }),
    )
    .superRefine((rows, ctx) => {
      const seen = new Set<string>()
      rows.forEach((row, i) => {
        if (!row.skillId) return
        if (seen.has(row.skillId))
          ctx.addIssue({
            code: 'custom',
            path: [i, 'skillId'],
            message: 'Each skill can be listed once.',
          })
        seen.add(row.skillId)
      })
    }),
})
type JobRoleValues = z.infer<typeof jobRoleSchema>
const JOB_ROLE_FIELDS = ['title', 'departmentId', 'description'] as const
const ROW_ERROR = /^requiredSkills\[(\d+)\]\.(skillId|minimumLevel)$/

/** Settings → HRMS → Job roles: titles with the skills (and minimum levels) they need. */
export function JobRolesSection() {
  const api = useApi()
  const queryClient = useQueryClient()
  const active = useQuery({
    queryKey: ['hr', 'job-roles', false],
    queryFn: () => api.get<JobRoleView[]>('/hr/job-roles'),
  })
  const archived = useQuery({
    queryKey: ['hr', 'job-roles', true],
    queryFn: () => api.get<JobRoleView[]>('/hr/job-roles?archived=true'),
  })
  const [editing, setEditing] = useState<JobRoleView | 'new' | null>(null)
  const [archiving, setArchiving] = useState<JobRoleView | null>(null)
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
    <section aria-label="Job roles" className="space-y-3">
      <div className="flex items-center justify-between">
        <h2 className="text-lg font-semibold">Job roles</h2>
        <Button onClick={() => setEditing('new')}>New job role</Button>
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
                <TableHead>Title</TableHead>
                <TableHead>Department</TableHead>
                <TableHead>Required skills</TableHead>
                <TableHead className="text-right">Actions</TableHead>
              </TableRow>
            </TableHeader>
            <TableBody>
              {[...active.data, ...(archived.data ?? [])].map((r) => (
                <TableRow key={r.id}>
                  <TableCell>
                    {r.title} {r.archivedAt && <Badge variant="outline">Archived</Badge>}
                  </TableCell>
                  <TableCell>{r.department?.name ?? '—'}</TableCell>
                  <TableCell className="max-w-md whitespace-normal">
                    {r.requiredSkills.length === 0
                      ? '—'
                      : r.requiredSkills.map((s) => `${s.name} (${s.minimumLevel})`).join(', ')}
                  </TableCell>
                  <TableCell className="space-x-2 text-right">
                    {r.archivedAt ? (
                      <Button
                        size="sm"
                        variant="outline"
                        disabled={busy}
                        aria-label={`Restore ${r.title}`}
                        onClick={() =>
                          void act(
                            () => api.post(`/hr/job-roles/${r.id}/restore`),
                            `${r.title} restored.`,
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
                          aria-label={`Edit ${r.title}`}
                          onClick={() => setEditing(r)}
                        >
                          Edit
                        </Button>
                        <Button
                          size="sm"
                          variant="outline"
                          aria-label={`Archive ${r.title}`}
                          onClick={() => {
                            setError(null)
                            setArchiving(r)
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
        <JobRoleDialog
          jobRole={editing === 'new' ? undefined : editing}
          onClose={() => setEditing(null)}
        />
      )}
      <ConfirmDialog
        open={archiving !== null}
        title={`Archive ${archiving?.title ?? ''}?`}
        description="It can't be given to employees any more. People who already have it keep it."
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
            () => api.post(`/hr/job-roles/${archiving.id}/archive`),
            `${archiving.title} archived.`,
          ).then((ok) => ok && setArchiving(null))
        }}
      />
    </section>
  )
}

function JobRoleDialog({ jobRole, onClose }: { jobRole?: JobRoleView; onClose: () => void }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const departments = useQuery({
    queryKey: ['hr', 'departments', false],
    queryFn: () => api.get<DepartmentView[]>('/hr/departments'),
  })
  const skills = useQuery({
    queryKey: ['hr', 'skills', false],
    queryFn: () => api.get<SkillView[]>('/hr/skills'),
  })
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<JobRoleValues>({
    resolver: zodResolver(jobRoleSchema),
    defaultValues: {
      title: jobRole?.title ?? '',
      departmentId: jobRole?.department?.id ?? '',
      description: jobRole?.description ?? '',
      requiredSkills: (jobRole?.requiredSkills ?? []).map((s) => ({
        skillId: s.skillId,
        minimumLevel: String(s.minimumLevel),
      })),
    },
  })
  const rows = useFieldArray({ control: form.control, name: 'requiredSkills' })

  // The role's current skills and department stay choosable even if they were archived since.
  const departmentOptions = [...(departments.data ?? [])]
  if (jobRole?.department && !departmentOptions.some((d) => d.id === jobRole.department?.id))
    departmentOptions.push({
      id: jobRole.department.id,
      name: jobRole.department.name,
      description: null,
      archivedAt: null,
      version: 0,
    })
  const skillOptions = [...(skills.data ?? [])]
  for (const s of jobRole?.requiredSkills ?? [])
    if (!skillOptions.some((o) => o.id === s.skillId))
      skillOptions.push({ id: s.skillId, name: s.name, archivedAt: null, version: 0 })

  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      title: values.title.trim(),
      departmentId: values.departmentId || null,
      description: values.description.trim() || null,
      requiredSkills: values.requiredSkills.map((s) => ({
        skillId: s.skillId,
        minimumLevel: Number(s.minimumLevel),
      })),
      ...(jobRole ? { version: jobRole.version } : {}),
    }
    try {
      if (jobRole) await api.put(`/hr/job-roles/${jobRole.id}`, body)
      else await api.post('/hr/job-roles', body)
      await invalidateHr(queryClient)
      toast.success(jobRole ? 'Changes saved.' : `${body.title} created.`)
      onClose()
    } catch (error) {
      if (jobRole && error instanceof ApiError && error.status === 409)
        void invalidateHr(queryClient)
      let applied = false
      for (const { field, message } of (error instanceof ApiError && error.problem.errors) || []) {
        const match = ROW_ERROR.exec(field)
        if (match) {
          form.setError(
            `requiredSkills.${match[1]}.${match[2]}` as FieldPath<JobRoleValues>,
            { type: 'server', message },
            { shouldFocus: !applied },
          )
          applied = true
        }
      }
      if (!applyFieldErrors(error, form.setError, JOB_ROLE_FIELDS) && !applied)
        setFormError(problemMessage(error))
    }
  })
  const rowErrors = form.formState.errors.requiredSkills

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{jobRole ? `Edit ${jobRole.title}` : 'New job role'}</DialogTitle>
          <DialogDescription>
            The skills a person in this role needs, and the level they should reach.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <TextField form={form} name="title" label="Title" maxLength={80} />
          <Field id="field-departmentId" label="Department">
            <NativeSelect id="field-departmentId" {...form.register('departmentId')}>
              <option value="">No department</option>
              {departmentOptions.map((d) => (
                <option key={d.id} value={d.id}>
                  {d.name}
                </option>
              ))}
            </NativeSelect>
          </Field>
          <TextAreaField form={form} name="description" label="Description" maxLength={2000} />
          <fieldset className="space-y-3">
            <legend className="text-sm font-medium">Required skills</legend>
            {rows.fields.length === 0 && (
              <p className="text-sm text-muted-foreground">No skills required.</p>
            )}
            {rows.fields.map((row, i) => {
              const skillError = rowErrors?.[i]?.skillId?.message
              const skillId = `field-requiredSkills-${i}-skillId`
              return (
                <div key={row.id} className="grid items-start gap-3 sm:grid-cols-[1fr_1fr_auto]">
                  <Field id={skillId} label={`Skill ${i + 1}`} error={skillError}>
                    <NativeSelect
                      id={skillId}
                      aria-invalid={skillError ? true : undefined}
                      aria-describedby={describedBy(skillId, skillError)}
                      {...form.register(`requiredSkills.${i}.skillId`)}
                    >
                      <option value="">Choose…</option>
                      {skillOptions.map((s) => (
                        <option key={s.id} value={s.id}>
                          {s.name}
                        </option>
                      ))}
                    </NativeSelect>
                  </Field>
                  <Controller
                    control={form.control}
                    name={`requiredSkills.${i}.minimumLevel`}
                    render={({ field }) => (
                      <SkillLevelSelect
                        id={`field-requiredSkills-${i}-minimumLevel`}
                        label={`Skill ${i + 1} minimum level`}
                        value={field.value}
                        onChange={field.onChange}
                        error={rowErrors?.[i]?.minimumLevel?.message}
                      />
                    )}
                  />
                  <Button
                    type="button"
                    variant="outline"
                    size="sm"
                    className="sm:mt-6"
                    aria-label={`Remove skill ${i + 1}`}
                    onClick={() => rows.remove(i)}
                  >
                    Remove
                  </Button>
                </div>
              )
            })}
            <Button
              type="button"
              variant="outline"
              size="sm"
              onClick={() => rows.append({ skillId: '', minimumLevel: '' })}
            >
              Add skill
            </Button>
          </fieldset>
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {jobRole ? 'Save changes' : 'Create job role'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
