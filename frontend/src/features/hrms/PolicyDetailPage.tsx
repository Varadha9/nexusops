import { useQuery, useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { Link, useParams } from 'react-router'
import { toast } from 'sonner'
import { ConfirmDialog } from '@/components/ConfirmDialog'
import { FormError } from '@/components/form/FormError'
import { ErrorState, ListSkeleton, PageHeader } from '@/components/states'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { useApi } from '@/lib/api/ApiContext'
import { problemMessage } from '@/lib/api/problems'
import type { PolicyView } from '@/lib/api/types'
import { formatDateTime } from '@/lib/format'
import { invalidateHr } from './invalidation'
import { POLICY_STATUS_LABELS } from './labels'
import { PolicyFormDialog } from './PolicyFormDialog'

function PolicyDetail({ policyId }: { policyId: string }) {
  const api = useApi()
  const can = useCan()
  const queryClient = useQueryClient()
  const key = ['hr', 'policy', policyId]
  const policy = useQuery({
    queryKey: key,
    queryFn: () => api.get<PolicyView>(`/hr/policies/${policyId}`),
  })
  const [dialog, setDialog] = useState<'edit' | 'archive' | null>(null)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState<string | null>(null)

  async function run(
    action: 'publish' | 'unpublish' | 'archive',
    version: number,
    success: string,
  ) {
    setBusy(true)
    setError(null)
    try {
      const updated = await api.post<PolicyView>(`/hr/policies/${policyId}/${action}`, {
        version,
      })
      queryClient.setQueryData(key, updated)
      await invalidateHr(queryClient)
      toast.success(success)
      return true
    } catch (e) {
      setError(problemMessage(e))
      void queryClient.invalidateQueries({ queryKey: key })
      return false
    } finally {
      setBusy(false)
    }
  }

  const back = (
    <Link to="/app/hrms/policies" className="text-sm underline-offset-4 hover:underline">
      ← Policies
    </Link>
  )
  if (policy.isPending)
    return (
      <>
        {back}
        <ListSkeleton />
      </>
    )
  if (policy.isError)
    return (
      <>
        {back}
        <ErrorState error={policy.error} onRetry={() => void policy.refetch()} />
      </>
    )
  const p = policy.data
  const archived = p.status === 'ARCHIVED'
  const canManage = can(PERMISSIONS.hrPolicy) && !archived

  return (
    <div className="space-y-6">
      {back}
      <PageHeader
        title={p.title}
        description={[
          p.author ? `by ${p.author.name}` : null,
          p.publishedAt ? `published ${formatDateTime(p.publishedAt)}` : null,
        ]
          .filter(Boolean)
          .join(' · ')}
        actions={
          <>
            <Badge variant={p.status === 'PUBLISHED' ? 'secondary' : 'outline'}>
              {POLICY_STATUS_LABELS[p.status]}
            </Badge>
            {canManage && (
              <>
                <Button variant="outline" size="sm" onClick={() => setDialog('edit')}>
                  Edit
                </Button>
                {p.status === 'DRAFT' ? (
                  <Button
                    size="sm"
                    disabled={busy}
                    onClick={() => void run('publish', p.version, 'Policy published.')}
                  >
                    Publish
                  </Button>
                ) : (
                  <Button
                    variant="outline"
                    size="sm"
                    disabled={busy}
                    onClick={() => void run('unpublish', p.version, 'Policy moved back to drafts.')}
                  >
                    Unpublish
                  </Button>
                )}
                <Button
                  variant="outline"
                  size="sm"
                  disabled={busy}
                  onClick={() => {
                    setError(null)
                    setDialog('archive')
                  }}
                >
                  Archive
                </Button>
              </>
            )}
          </>
        }
      />
      {!dialog && <FormError message={error} />}
      <Card>
        <CardContent className="pt-6">
          <p className="whitespace-pre-wrap">{p.body}</p>
        </CardContent>
      </Card>
      <p className="text-sm text-muted-foreground">Last updated {formatDateTime(p.updatedAt)}</p>
      {dialog === 'edit' && (
        <PolicyFormDialog
          policy={p}
          onClose={() => setDialog(null)}
          onSaved={() => setDialog(null)}
        />
      )}
      <ConfirmDialog
        open={dialog === 'archive'}
        title={`Archive ${p.title}?`}
        description="It can't be published again."
        confirmLabel="Archive policy"
        busy={busy}
        error={error}
        onCancel={() => setDialog(null)}
        onConfirm={() =>
          void run('archive', p.version, 'Policy archived.').then((ok) => ok && setDialog(null))
        }
      />
    </div>
  )
}

/** Keyed by the route param so a different policy starts with fresh dialog and error state. */
export function PolicyDetailPage() {
  const { policyId = '' } = useParams()
  return <PolicyDetail key={policyId} policyId={policyId} />
}
