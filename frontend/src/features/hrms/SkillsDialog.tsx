import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Controller, useFieldArray, useForm, type FieldPath } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
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
import { ApiError } from '@/lib/api/client'
import { problemMessage } from '@/lib/api/problems'
import type { EmployeeView, SkillView } from '@/lib/api/types'
import { employeeKey } from './EmployeeFormDialog'
import { invalidateHr } from './invalidation'
import { SkillLevelSelect } from './SkillLevelSelect'

const schema = z.object({
  skills: z
    .array(
      z.object({
        skillId: z.string().min(1, 'Choose a skill.'),
        level: z.string().min(1, 'Choose a level.'),
        note: z.string().trim().max(200, 'Use at most 200 characters.'),
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
type Values = z.infer<typeof schema>
const ROW_ERROR = /^skills\[(\d+)\]\.(skillId|level|note)$/

/** Edits the skills an employee holds as a whole: the list is replaced on save. */
export function SkillsDialog({
  employee,
  onClose,
}: {
  employee: EmployeeView
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const skills = useQuery({
    queryKey: ['hr', 'skills', false],
    queryFn: () => api.get<SkillView[]>('/hr/skills'),
  })
  const [formError, setFormError] = useState<string | null>(null)
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      skills: employee.skills.map((s) => ({
        skillId: s.skillId,
        level: String(s.level),
        note: s.note ?? '',
      })),
    },
  })
  const rows = useFieldArray({ control: form.control, name: 'skills' })

  // A skill they already hold stays choosable even if it was archived since.
  const skillOptions = [...(skills.data ?? [])]
  for (const s of employee.skills)
    if (!skillOptions.some((o) => o.id === s.skillId))
      skillOptions.push({ id: s.skillId, name: s.name, archivedAt: null, version: 0 })

  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    try {
      const saved = await api.put<EmployeeView>(`/hr/employees/${employee.id}/skills`, {
        skills: values.skills.map((s) => ({
          skillId: s.skillId,
          level: Number(s.level),
          note: s.note.trim() || null,
        })),
        version: employee.version,
      })
      queryClient.setQueryData(employeeKey(saved.id), saved)
      await invalidateHr(queryClient)
      toast.success('Skills saved.')
      onClose()
    } catch (error) {
      if (error instanceof ApiError && error.status === 409)
        void queryClient.invalidateQueries({ queryKey: employeeKey(employee.id) })
      let applied = false
      for (const { field, message } of (error instanceof ApiError && error.problem.errors) || []) {
        const match = ROW_ERROR.exec(field)
        if (match) {
          form.setError(
            `skills.${match[1]}.${match[2]}` as FieldPath<Values>,
            { type: 'server', message },
            { shouldFocus: !applied },
          )
          applied = true
        }
      }
      if (!applied) setFormError(problemMessage(error))
    }
  })
  const rowErrors = form.formState.errors.skills

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>Skills of {employee.person.name}</DialogTitle>
          <DialogDescription>
            The skills they hold and how well. Saving replaces the whole list.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <fieldset className="space-y-3">
            <legend className="sr-only">Skills</legend>
            {rows.fields.length === 0 && (
              <p className="text-sm text-muted-foreground">No skills recorded.</p>
            )}
            {rows.fields.map((row, i) => {
              const skillError = rowErrors?.[i]?.skillId?.message
              const noteError = rowErrors?.[i]?.note?.message
              const skillId = `field-skills-${i}-skillId`
              const noteId = `field-skills-${i}-note`
              return (
                <div
                  key={row.id}
                  className="grid items-start gap-3 sm:grid-cols-[1fr_1fr_1fr_auto]"
                >
                  <Field id={skillId} label={`Skill ${i + 1}`} error={skillError}>
                    <NativeSelect
                      id={skillId}
                      aria-invalid={skillError ? true : undefined}
                      aria-describedby={describedBy(skillId, skillError)}
                      {...form.register(`skills.${i}.skillId`)}
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
                    name={`skills.${i}.level`}
                    render={({ field }) => (
                      <SkillLevelSelect
                        id={`field-skills-${i}-level`}
                        label={`Skill ${i + 1} level`}
                        value={field.value}
                        onChange={field.onChange}
                        error={rowErrors?.[i]?.level?.message}
                      />
                    )}
                  />
                  <Field id={noteId} label={`Skill ${i + 1} note`} error={noteError}>
                    <Input
                      id={noteId}
                      maxLength={200}
                      aria-invalid={noteError ? true : undefined}
                      aria-describedby={describedBy(noteId, noteError)}
                      {...form.register(`skills.${i}.note`)}
                    />
                  </Field>
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
              onClick={() => rows.append({ skillId: '', level: '', note: '' })}
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
              Save skills
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
