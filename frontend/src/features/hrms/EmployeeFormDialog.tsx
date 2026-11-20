import { zodResolver } from '@hookform/resolvers/zod'
import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { useForm } from 'react-hook-form'
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
import { TextField } from '@/components/form/TextField'
import { requiredText } from '@/features/auth/schemas'
import { PartyPicker } from '@/features/records/PartyPicker'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { applyFieldErrors, problemMessage } from '@/lib/api/problems'
import type {
  DepartmentView,
  EmployeeView,
  EmploymentType,
  JobRoleView,
  OnboardingTemplateView,
} from '@/lib/api/types'
import { EmployeePicker } from './EmployeePicker'
import { invalidateHr } from './invalidation'
import { EMPLOYMENT_TYPE_LABELS } from './labels'
import { MemberSelect } from './MemberSelect'

export const employeeKey = (id: string) => ['hr', 'employee', id] as const

const schema = z
  .object({
    mode: z.enum(['existing', 'new']),
    personId: z.string(),
    firstName: z.string().trim().max(80, 'Use at most 80 characters.'),
    lastName: z.string().trim().max(80, 'Use at most 80 characters.'),
    email: z.string().trim().max(254, 'Use at most 254 characters.'),
    phone: z.string().trim().max(40, 'Use at most 40 characters.'),
    employeeNumber: requiredText(40),
    departmentId: z.string(),
    jobRoleId: z.string(),
    managerId: z.string(),
    employmentType: z.enum(['FULL_TIME', 'PART_TIME', 'CONTRACT', 'INTERN']),
    startDate: z.string().min(1, 'Enter a start date.'),
    userId: z.string(),
    onboardingTemplateId: z.string(),
  })
  .superRefine((v, ctx) => {
    if (v.mode === 'existing' && !v.personId)
      ctx.addIssue({ code: 'custom', path: ['personId'], message: 'Choose a person.' })
    if (v.mode === 'new' && !v.firstName)
      ctx.addIssue({ code: 'custom', path: ['firstName'], message: 'Required.' })
    if (v.mode === 'new' && !v.lastName)
      ctx.addIssue({ code: 'custom', path: ['lastName'], message: 'Required.' })
  })
type Values = z.infer<typeof schema>
const FIELDS = [
  'personId',
  'firstName',
  'lastName',
  'email',
  'phone',
  'employeeNumber',
  'departmentId',
  'jobRoleId',
  'managerId',
  'employmentType',
  'startDate',
  'userId',
  'onboardingTemplateId',
] as const

/** Hire someone (D2) — an existing directory person or a new one — or edit an employment record. */
export function EmployeeFormDialog({
  employee,
  onClose,
  onSaved,
}: {
  employee?: EmployeeView
  onClose: () => void
  onSaved: (saved: EmployeeView) => void
}) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [formError, setFormError] = useState<string | null>(null)
  const departments = useQuery({
    queryKey: ['hr', 'departments', false],
    queryFn: () => api.get<DepartmentView[]>('/hr/departments'),
  })
  const jobRoles = useQuery({
    queryKey: ['hr', 'job-roles', false],
    queryFn: () => api.get<JobRoleView[]>('/hr/job-roles'),
  })
  const templates = useQuery({
    queryKey: ['hr', 'onboarding-templates', false],
    queryFn: () => api.get<OnboardingTemplateView[]>('/hr/onboarding-templates'),
    enabled: !employee,
  })
  const form = useForm<Values>({
    resolver: zodResolver(schema),
    defaultValues: {
      mode: 'existing',
      personId: employee?.person.id ?? '',
      firstName: '',
      lastName: '',
      email: '',
      phone: '',
      employeeNumber: employee?.employeeNumber ?? '',
      departmentId: employee?.department?.id ?? '',
      jobRoleId: employee?.jobRole?.id ?? '',
      managerId: employee?.manager?.id ?? '',
      employmentType: employee?.employmentType ?? 'FULL_TIME',
      startDate: employee?.startDate ?? '',
      userId: employee?.user?.id ?? '',
      onboardingTemplateId: '',
    },
  })
  const errors = form.formState.errors
  const revalidate = { shouldValidate: form.formState.isSubmitted }
  const mode = form.watch('mode')

  const onSubmit = form.handleSubmit(async (v) => {
    setFormError(null)
    const shared = {
      employeeNumber: v.employeeNumber.trim(),
      departmentId: v.departmentId || null,
      jobRoleId: v.jobRoleId || null,
      managerId: v.managerId || null,
      employmentType: v.employmentType,
      startDate: v.startDate,
      userId: v.userId || null,
    }
    const body = employee
      ? { ...shared, version: employee.version }
      : {
          personId: v.mode === 'existing' ? v.personId : null,
          person:
            v.mode === 'new'
              ? {
                  firstName: v.firstName.trim(),
                  lastName: v.lastName.trim(),
                  email: v.email.trim() || null,
                  phone: v.phone.trim() || null,
                }
              : null,
          ...shared,
          onboardingTemplateId: v.onboardingTemplateId || null,
        }
    try {
      const saved = employee
        ? await api.put<EmployeeView>(`/hr/employees/${employee.id}`, body)
        : await api.post<EmployeeView>('/hr/employees', body)
      queryClient.setQueryData(employeeKey(saved.id), saved)
      await invalidateHr(queryClient)
      toast.success(employee ? 'Changes saved.' : `${saved.person.name} hired.`)
      onSaved(saved)
    } catch (error) {
      if (employee && error instanceof ApiError && error.status === 409)
        void queryClient.invalidateQueries({ queryKey: employeeKey(employee.id) })
      // the directory reports a new person's fields without the "person." prefix
      const prefixed =
        error instanceof ApiError
          ? error.problem.errors?.find((e) => e.field.startsWith('person.'))
          : undefined
      if (prefixed)
        form.setError(prefixed.field.slice('person.'.length) as 'firstName' | 'lastName', {
          type: 'server',
          message: prefixed.message,
        })
      if (!applyFieldErrors(error, form.setError, FIELDS) && !prefixed)
        setFormError(problemMessage(error))
    }
  })

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="max-h-[90vh] overflow-y-auto sm:max-w-2xl">
        <DialogHeader>
          <DialogTitle>{employee ? `Edit ${employee.person.name}` : 'Hire employee'}</DialogTitle>
          <DialogDescription>
            {employee
              ? 'Employment details. Contact details live on the person in the directory.'
              : 'Choose someone already in the directory, or add a new person.'}
          </DialogDescription>
        </DialogHeader>
        <form noValidate onSubmit={(e) => void onSubmit(e)} className="space-y-4">
          {!employee && (
            <>
              <fieldset className="flex gap-4 text-sm">
                <legend className="sr-only">Who</legend>
                <label className="flex items-center gap-2">
                  <input type="radio" value="existing" {...form.register('mode')} /> Existing person
                </label>
                <label className="flex items-center gap-2">
                  <input type="radio" value="new" {...form.register('mode')} /> New person
                </label>
              </fieldset>
              {mode === 'existing' ? (
                <PartyPicker
                  id="field-personId"
                  label="Person"
                  kind="PERSON"
                  value={form.watch('personId')}
                  error={errors.personId?.message}
                  noneLabel="Choose…"
                  onChange={(id) => form.setValue('personId', id, revalidate)}
                />
              ) : (
                <div className="grid gap-3 sm:grid-cols-2">
                  <TextField form={form} name="firstName" label="First name" maxLength={80} />
                  <TextField form={form} name="lastName" label="Last name" maxLength={80} />
                  <TextField form={form} name="email" label="Email" type="email" />
                  <TextField form={form} name="phone" label="Phone" maxLength={40} />
                </div>
              )}
            </>
          )}
          <div className="grid gap-3 sm:grid-cols-2">
            <TextField form={form} name="employeeNumber" label="Employee number" maxLength={40} />
            <Field id="field-startDate" label="Start date" error={errors.startDate?.message}>
              <Input
                id="field-startDate"
                type="date"
                aria-invalid={errors.startDate ? true : undefined}
                aria-describedby={describedBy('field-startDate', errors.startDate?.message)}
                {...form.register('startDate')}
              />
            </Field>
            <Field id="field-departmentId" label="Department" error={errors.departmentId?.message}>
              <NativeSelect id="field-departmentId" {...form.register('departmentId')}>
                <option value="">No department</option>
                {(departments.data ?? []).map((d) => (
                  <option key={d.id} value={d.id}>
                    {d.name}
                  </option>
                ))}
              </NativeSelect>
            </Field>
            <Field id="field-jobRoleId" label="Job role" error={errors.jobRoleId?.message}>
              <NativeSelect id="field-jobRoleId" {...form.register('jobRoleId')}>
                <option value="">No job role</option>
                {(jobRoles.data ?? []).map((r) => (
                  <option key={r.id} value={r.id}>
                    {r.title}
                  </option>
                ))}
              </NativeSelect>
            </Field>
            <Field id="field-employmentType" label="Employment type">
              <NativeSelect id="field-employmentType" {...form.register('employmentType')}>
                {(Object.keys(EMPLOYMENT_TYPE_LABELS) as EmploymentType[]).map((t) => (
                  <option key={t} value={t}>
                    {EMPLOYMENT_TYPE_LABELS[t]}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          </div>
          <EmployeePicker
            id="field-managerId"
            label="Manager"
            value={form.watch('managerId')}
            current={employee?.manager}
            error={errors.managerId?.message}
            onChange={(id) => form.setValue('managerId', id, revalidate)}
          />
          <MemberSelect
            id="field-userId"
            label="Team member account"
            value={form.watch('userId')}
            current={employee?.user}
            error={errors.userId?.message}
            onChange={(id) => form.setValue('userId', id, revalidate)}
          />
          {!employee && (
            <Field
              id="field-onboardingTemplateId"
              label="Onboarding"
              error={errors.onboardingTemplateId?.message}
            >
              <NativeSelect
                id="field-onboardingTemplateId"
                aria-invalid={errors.onboardingTemplateId ? true : undefined}
                aria-describedby={describedBy(
                  'field-onboardingTemplateId',
                  errors.onboardingTemplateId?.message,
                )}
                {...form.register('onboardingTemplateId')}
              >
                <option value="">Start later</option>
                {(templates.data ?? []).map((t) => (
                  <option key={t.id} value={t.id}>
                    {t.name}
                  </option>
                ))}
              </NativeSelect>
            </Field>
          )}
          <FormError message={formError} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={form.formState.isSubmitting}>
              {employee ? 'Save changes' : 'Hire'}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
