import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
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
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { CourseRef, CourseView, EmployeeRef } from '@/lib/api/types'
import { EmployeePicker } from './EmployeePicker'
import { invalidateHr } from './invalidation'

/** Assigns a course to an employee. Either side may be fixed by the caller; the other is chosen here. */
export function AssignTrainingDialog({
  employee,
  course,
  onClose,
}: {
  employee?: EmployeeRef
  course?: CourseRef
  onClose: () => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [courseId, setCourseId] = useState(course?.id ?? '')
  const [employeeId, setEmployeeId] = useState(employee?.id ?? '')
  const [dueOn, setDueOn] = useState('')
  const [errors, setErrors] = useState<{ course?: string; employee?: string }>({})
  const [formError, setFormError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const courses = useQuery({
    queryKey: ['hr', 'courses', false],
    queryFn: () => api.get<CourseView[]>('/hr/courses'),
    enabled: !course,
  })

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setFormError(null)
    const next = {
      course: courseId ? undefined : 'Choose a course.',
      employee: employeeId ? undefined : 'Choose an employee.',
    }
    setErrors(next)
    if (next.course || next.employee) return
    setBusy(true)
    try {
      await api.post('/hr/training-assignments', { courseId, employeeId, dueOn: dueOn || null })
      await invalidateHr(queryClient)
      toast.success('Training assigned.')
      onClose()
    } catch (error) {
      setFormError(problemMessage(error))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto">
        <DialogHeader>
          <DialogTitle>Assign training</DialogTitle>
          <DialogDescription>
            {[course?.title, employee?.name].filter(Boolean).join(' · ') ||
              'Pick a course and who should take it.'}
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void submit(e)} className="space-y-4">
          {!course && (
            <Field id="field-courseId" label="Course" error={errors.course}>
              <NativeSelect
                id="field-courseId"
                value={courseId}
                aria-invalid={errors.course ? true : undefined}
                aria-describedby={describedBy('field-courseId', errors.course)}
                onChange={(e) => setCourseId(e.target.value)}
              >
                <option value="">Choose…</option>
                {(courses.data ?? []).map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.title}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          )}
          {!employee && (
            <EmployeePicker
              id="field-employeeId"
              label="Employee"
              value={employeeId}
              error={errors.employee}
              blankLabel="Choose…"
              onChange={setEmployeeId}
            />
          )}
          <Field id="field-dueOn" label="Due">
            <Input
              id="field-dueOn"
              type="date"
              value={dueOn}
              onChange={(e) => setDueOn(e.target.value)}
            />
          </Field>
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={busy}>
              Assign
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
