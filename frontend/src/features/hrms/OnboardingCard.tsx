import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { Label } from '@/components/ui/label'
import { FormError } from '@/components/form/FormError'
import { NativeSelect } from '@/components/form/NativeSelect'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { EmployeeView, OnboardingTemplateView } from '@/lib/api/types'
import { employeeKey } from './EmployeeFormDialog'
import { invalidateHr } from './invalidation'

/** Onboarding progress, or — for those who can edit — a way to start it from a template. */
export function OnboardingCard({ employee }: { employee: EmployeeView }) {
  const can = useCan()
  const { onboarding } = employee
  const canStart = !onboarding && can(PERMISSIONS.hrEmployeeUpdate)
  return (
    <Card role="region" aria-label="Onboarding">
      <CardHeader>
        <CardTitle>Onboarding</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        {onboarding ? (
          <>
            <p className="font-medium">{onboarding.templateName}</p>
            <progress
              className="w-full"
              value={onboarding.done}
              max={onboarding.total}
              aria-label="Onboarding progress"
            />
            <p className="text-muted-foreground">
              {onboarding.done} of {onboarding.total} tasks done
            </p>
            <a href="#tasks" className="underline-offset-4 hover:underline">
              See the tasks
            </a>
          </>
        ) : canStart ? (
          <StartOnboarding employee={employee} />
        ) : (
          <p className="text-muted-foreground">No onboarding started.</p>
        )}
      </CardContent>
    </Card>
  )
}

function StartOnboarding({ employee }: { employee: EmployeeView }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [templateId, setTemplateId] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const templates = useQuery({
    queryKey: ['hr', 'onboarding-templates', false],
    queryFn: () => api.get<OnboardingTemplateView[]>('/hr/onboarding-templates'),
  })

  async function start() {
    if (!templateId) {
      setError('Choose a template.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      const saved = await api.post<EmployeeView>(`/hr/employees/${employee.id}/onboarding`, {
        templateId,
      })
      queryClient.setQueryData(employeeKey(saved.id), saved)
      await invalidateHr(queryClient)
      toast.success('Onboarding started.')
    } catch (e) {
      setError(problemMessage(e))
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="space-y-3">
      <p className="text-muted-foreground">No onboarding started.</p>
      <div className="space-y-1.5">
        <Label htmlFor="onboarding-template">Template</Label>
        <NativeSelect
          id="onboarding-template"
          value={templateId}
          onChange={(e) => setTemplateId(e.target.value)}
        >
          <option value="">Choose…</option>
          {(templates.data ?? []).map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </NativeSelect>
      </div>
      <FormError message={error} />
      <Button size="sm" disabled={busy} onClick={() => void start()}>
        Start onboarding
      </Button>
    </div>
  )
}
