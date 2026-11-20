import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Controller, useFieldArray, useForm, type FieldPath } from 'react-hook-form'
import { toast } from 'sonner'
import { z } from 'zod'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
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
import type { CourseView, SkillView } from '@/lib/api/types'
import { invalidateHr } from './invalidation'
import { SkillLevelSelect } from './SkillLevelSelect'

const DURATION_MESSAGE = 'Use more than 0 and at most 999 hours.'
const LINK_MESSAGE = 'Use a web address starting with http:// or https://.'

const schema = z.object({
  title: requiredText(200),
  description: z.string().trim().max(5000, 'Use at most 5000 characters.'),
  durationHours: z
    .string()
    .trim()
    .refine((v) => {
      if (v === '') return true
      const n = Number(v)
      return Number.isFinite(n) && n > 0 && n <= 999
    }, DURATION_MESSAGE),
  link: z
    .string()
    .trim()
    .max(500, 'Use at most 500 characters.')
    .refine((v) => v === '' || /^https?:\/\/\S+$/i.test(v), LINK_MESSAGE),
  skills: z
    .array(
      z.object({
        skillId: z.string().min(1, 'Choose a skill.'),
        level: z.string().min(1, 'Choose a level.'),
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
const FIELDS = ['title', 'description', 'durationHours', 'link'] as const
const ROW_ERROR = /^skills\[(\d+)\]\.(skillId|level)$/

/** Creates a course, or edits one: what it covers, how long it takes and the skills it teaches. */
export function CourseFormDialog({
  course,
  onClose,
}: {
  course?: CourseView
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const skills = useQuery({
    queryKey: ['hr', 'skills', false],
    queryFn: () => api.get<SkillView[]>('/hr/skills'),
  })
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      title: course?.title ?? '',
      description: course?.description ?? '',
      durationHours: course?.durationHours != null ? String(course.durationHours) : '',
      link: course?.link ?? '',
      skills: (course?.skills ?? []).map((s) => ({ skillId: s.skillId, level: String(s.level) })),
    },
  })
  const rows = useFieldArray({ control: form.control, name: 'skills' })

  // Skills the course already teaches stay choosable even if they were archived since.
  const skillOptions = [...(skills.data ?? [])]
  for (const s of course?.skills ?? [])
    if (!skillOptions.some((o) => o.id === s.skillId))
      skillOptions.push({ id: s.skillId, name: s.name, archivedAt: null, version: 0 })

  const onSubmit = form.handleSubmit(async (values) => {
    setFormError(null)
    const body = {
      title: values.title.trim(),
      description: values.description.trim() || null,
      durationHours: values.durationHours.trim() === '' ? null : Number(values.durationHours),
      link: values.link.trim() || null,
      skills: values.skills.map((s) => ({ skillId: s.skillId, level: Number(s.level) })),
      ...(course ? { version: course.version } : {}),
    }
    try {
      if (course) await api.put(`/hr/courses/${course.id}`, body)
      else await api.post('/hr/courses', body)
      await invalidateHr(queryClient)
      toast.success(course ? 'Changes saved.' : `${body.title} created.`)
      onClose()
    } catch (error) {
      if (course && error instanceof ApiError && error.status === 409)
        void invalidateHr(queryClient)
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
      if (!applyFieldErrors(error, form.setError, FIELDS) && !applied)
        setFormError(problemMessage(error))
    }
  })
  const rowErrors = form.formState.errors.skills

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>{course ? `Edit ${course.title}` : 'New course'}</DialogTitle>
          <DialogDescription>
            The skills a course teaches are used to suggest it to people with a gap.
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          <TextField form={form} name="title" label="Title" maxLength={200} />
          <TextAreaField form={form} name="description" label="Description" maxLength={5000} />
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField
              form={form}
              name="durationHours"
              label="Duration (hours)"
              inputMode="decimal"
            />
            <TextField form={form} name="link" label="Link" maxLength={500} />
          </div>
          <fieldset className="space-y-3">
            <legend className="text-sm font-medium">Skills taught</legend>
            {rows.fields.length === 0 && (
              <p className="text-sm text-muted-foreground">No skills taught.</p>
            )}
            {rows.fields.map((row, i) => {
              const skillError = rowErrors?.[i]?.skillId?.message
              const skillId = `field-skills-${i}-skillId`
              return (
                <div key={row.id} className="grid items-start gap-3 sm:grid-cols-[1fr_1fr_auto]">
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
              onClick={() => rows.append({ skillId: '', level: '' })}
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
              {course ? 'Save changes' : 'Create course'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
