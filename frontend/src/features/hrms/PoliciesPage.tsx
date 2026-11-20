import { keepPreviousData, useQuery } from '@tanstack/react-query'
import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router'
import { NativeSelect } from '@/components/form/NativeSelect'
import { Pagination } from '@/components/Pagination'
import { EmptyState, ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import type { Page, PolicyStatus, PolicySummary } from '@/lib/api/types'
import { formatDate } from '@/lib/format'
import { toQuery } from '@/lib/query'
import { POLICY_STATUS_LABELS } from './labels'
import { PolicyFormDialog } from './PolicyFormDialog'

const SIZE = 20

/** Staff see published policies; policy managers also see drafts, and archived ones on request. */
export function PoliciesPage() {
  const api = useApi()
  const can = useCan()
  const navigate = useNavigate()
  const [params, setParams] = useSearchParams()
  const canManage = can(PERMISSIONS.hrPolicy)
  const q = params.get('q') ?? ''
  const status = canManage ? (params.get('status') ?? '') : ''
  const page = Math.max(0, Number(params.get('page') ?? '0') || 0)
  const [creating, setCreating] = useState(false)

  const policies = useQuery({
    queryKey: ['hr', 'policies', { q, status, page }],
    queryFn: () =>
      api.get<Page<PolicySummary>>(`/hr/policies?${toQuery({ q, status, page, size: SIZE })}`),
    placeholderData: keepPreviousData,
    refetchOnMount: 'always',
  })

  function update(next: Record<string, string>) {
    const merged = new URLSearchParams(params)
    for (const [key, value] of Object.entries(next)) {
      if (value) merged.set(key, value)
      else merged.delete(key)
    }
    setParams(merged, { replace: true })
  }

  function onSearch(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    const value = new FormData(event.currentTarget).get('q')
    update({ q: typeof value === 'string' ? value.trim() : '', page: '' })
  }

  return (
    <>
      <PageHeader
        title="Policies"
        description="The rules everyone works by. Published policies are visible to everyone with self-service."
        actions={canManage && <Button onClick={() => setCreating(true)}>New policy</Button>}
      />
      <div className="mb-4 flex flex-wrap items-end gap-3">
        <form onSubmit={onSearch} className="flex items-end gap-2" role="search">
          <div className="space-y-1.5">
            <Label htmlFor="policy-q">Search policies</Label>
            <Input
              id="policy-q"
              key={q}
              name="q"
              defaultValue={q}
              placeholder="Words in the title or text"
            />
          </div>
          <Button type="submit" variant="outline">
            Search
          </Button>
        </form>
        {canManage && (
          <div className="space-y-1.5">
            <Label htmlFor="policy-status">Status</Label>
            <NativeSelect
              id="policy-status"
              value={status}
              onChange={(e) => update({ status: e.target.value, page: '' })}
            >
              <option value="">Drafts and published</option>
              {(Object.keys(POLICY_STATUS_LABELS) as PolicyStatus[]).map((s) => (
                <option key={s} value={s}>
                  {POLICY_STATUS_LABELS[s]}
                </option>
              ))}
            </NativeSelect>
          </div>
        )}
      </div>
      {policies.isPending ? (
        <ListSkeleton />
      ) : policies.isError ? (
        <ErrorState error={policies.error} onRetry={() => void policies.refetch()} />
      ) : policies.data.items.length === 0 ? (
        <EmptyState
          title={q || status ? 'No policies match.' : 'No policies yet.'}
          description={
            canManage
              ? 'Write down the rules people ask about most.'
              : 'Published policies will appear here.'
          }
        />
      ) : (
        <>
          <ul className="divide-y rounded-lg border">
            {policies.data.items.map((p) => (
              <li key={p.id} className="space-y-1 p-4">
                <div className="flex flex-wrap items-center gap-2">
                  <Link
                    to={`/app/hrms/policies/${p.id}`}
                    className="font-medium underline-offset-4 hover:underline"
                  >
                    {p.title}
                  </Link>
                  {p.status !== 'PUBLISHED' && (
                    <Badge variant="outline">{POLICY_STATUS_LABELS[p.status]}</Badge>
                  )}
                  <span className="text-sm text-muted-foreground">
                    Updated {formatDate(p.updatedAt.slice(0, 10))}
                  </span>
                </div>
                <p className="text-sm text-muted-foreground">{p.excerpt}</p>
              </li>
            ))}
          </ul>
          <Pagination
            page={policies.data.page}
            size={policies.data.size}
            total={policies.data.total}
            onPage={(p) => update({ page: String(p) })}
          />
        </>
      )}
      {creating && (
        <PolicyFormDialog
          onClose={() => setCreating(false)}
          onSaved={(saved) => {
            setCreating(false)
            navigate(`/app/hrms/policies/${saved.id}`)
          }}
        />
      )}
    </>
  )
}
