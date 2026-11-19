import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/form/Checkbox'
import { describedBy, Field } from '@/components/form/Field'
import { FormError } from '@/components/form/FormError'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { problemMessage } from '@/lib/api/problems'
import type { AiRebuildResult, AiSettingsView } from '@/lib/api/types'

const LIMIT_ERROR = 'Enter a limit from 1 to 100000.'

/** Settings → AI: turn assistance on or off, cap monthly requests, and rebuild the search index. */
export function AiSettingsPage() {
  const api = useApi()
  const settings = useQuery({
    queryKey: ['ai', 'settings'],
    queryFn: () => api.get<AiSettingsView>('/ai/settings'),
  })
  return (
    <>
      <PageHeader
        title="AI"
        description="Answers and suggestions drawn only from your workspace's own documents."
      />
      {settings.isPending ? (
        <ListSkeleton rows={4} />
      ) : settings.isError ? (
        <ErrorState error={settings.error} onRetry={() => void settings.refetch()} />
      ) : (
        <AiSettingsForm key={settings.data.version} settings={settings.data} />
      )}
    </>
  )
}

function AiSettingsForm({ settings }: { settings: AiSettingsView }) {
  const api = useApi()
  const queryClient = useQueryClient()
  const [enabled, setEnabled] = useState(settings.enabled)
  const [limit, setLimit] = useState(String(settings.monthlyLimit))
  const [limitError, setLimitError] = useState<string | undefined>()
  const [formError, setFormError] = useState<string | null>(null)
  const [rebuilt, setRebuilt] = useState<AiRebuildResult | null>(null)
  const [rebuildError, setRebuildError] = useState<string | null>(null)

  const save = useMutation({
    mutationFn: (body: { enabled: boolean; monthlyLimit: number; version: number }) =>
      api.put<AiSettingsView>('/ai/settings', body),
  })
  const rebuild = useMutation({
    mutationFn: () => api.post<AiRebuildResult>('/ai/index/rebuild'),
  })

  async function submit(event: React.FormEvent) {
    event.preventDefault()
    setFormError(null)
    const text = limit.trim()
    const monthlyLimit = Number(text)
    if (!/^\d+$/.test(text) || monthlyLimit < 1 || monthlyLimit > 100000) {
      setLimitError(LIMIT_ERROR)
      return
    }
    setLimitError(undefined)
    try {
      const saved = await save.mutateAsync({ enabled, monthlyLimit, version: settings.version })
      queryClient.setQueryData(['ai', 'settings'], saved)
      await queryClient.invalidateQueries({ queryKey: ['ai'] })
      toast.success('AI settings saved.')
    } catch (e) {
      const invalid =
        e instanceof ApiError
          ? e.problem.errors?.find((f) => f.field === 'monthlyLimit')
          : undefined
      if (invalid) setLimitError(invalid.message)
      else {
        setFormError(problemMessage(e))
        // a conflict means someone else saved first: reload so the next save carries the current version
        void queryClient.invalidateQueries({ queryKey: ['ai'] })
      }
    }
  }

  async function runRebuild() {
    setRebuildError(null)
    try {
      setRebuilt(await rebuild.mutateAsync())
      await queryClient.invalidateQueries({ queryKey: ['ai'] })
    } catch (e) {
      setRebuildError(problemMessage(e))
    }
  }

  const used = Math.min(settings.usedThisMonth, settings.monthlyLimit)
  const percent = Math.round((used / settings.monthlyLimit) * 100)
  return (
    <div className="max-w-xl space-y-8">
      <form noValidate onSubmit={(e) => void submit(e)} className="space-y-4">
        <div className="flex items-center gap-2">
          <Checkbox
            id="ai-enabled"
            role="switch"
            checked={enabled}
            onChange={(e) => setEnabled(e.target.checked)}
          />
          <Label htmlFor="ai-enabled">AI assistance is on</Label>
        </div>
        <Field
          id="ai-limit"
          label="Monthly request limit"
          error={limitError}
          hint="Counts every suggestion, draft and answer, for the whole workspace, per calendar month (UTC)."
        >
          <Input
            id="ai-limit"
            inputMode="numeric"
            value={limit}
            aria-invalid={limitError ? true : undefined}
            aria-describedby={describedBy('ai-limit', limitError, 'hint')}
            onChange={(e) => setLimit(e.target.value)}
          />
        </Field>
        <FormError message={formError} />
        <Button type="submit" disabled={save.isPending}>
          Save changes
        </Button>
      </form>

      <section aria-label="Usage" className="space-y-2">
        <h2 className="text-base font-semibold">Usage this month</h2>
        <p className="text-sm">
          <span>
            {settings.usedThisMonth} of {settings.monthlyLimit}
          </span>{' '}
          <span className="text-muted-foreground">requests used</span>
        </p>
        <div
          role="progressbar"
          aria-label="Requests used this month"
          aria-valuemin={0}
          aria-valuemax={settings.monthlyLimit}
          aria-valuenow={used}
          className="h-2 w-full overflow-hidden rounded-full bg-muted"
        >
          <div className="h-full bg-primary" style={{ width: `${percent}%` }} />
        </div>
      </section>

      <section aria-label="Search index" className="space-y-3">
        <h2 className="text-base font-semibold">Provider and index</h2>
        <dl className="grid grid-cols-[10rem_1fr] gap-x-3 gap-y-1 text-sm">
          <dt className="text-muted-foreground">Provider</dt>
          <dd>{settings.provider}</dd>
          <dt className="text-muted-foreground">Indexed passages</dt>
          <dd>{settings.indexedChunks}</dd>
        </dl>
        <p className="text-sm text-muted-foreground">
          Articles and policies are indexed when they are published. Rebuild if answers seem to be
          missing something.
        </p>
        <Button variant="outline" disabled={rebuild.isPending} onClick={() => void runRebuild()}>
          Rebuild index
        </Button>
        <FormError message={rebuildError} />
        {rebuilt && (
          <p role="status" className="text-sm">
            Indexed {rebuilt.indexedSources} documents ({rebuilt.indexedChunks} passages).
          </p>
        )}
      </section>
    </div>
  )
}
