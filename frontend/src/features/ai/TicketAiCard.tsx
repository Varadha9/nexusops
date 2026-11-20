import { useQueryClient } from '@tanstack/react-query'
import { useState } from 'react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/components/ui/card'
import { FormError } from '@/components/form/FormError'
import { PERMISSIONS, useCan } from '@/features/auth/permissions'
import { invalidateHelpDesk } from '@/features/helpdesk/invalidation'
import { PRIORITY_LABELS } from '@/features/helpdesk/labels'
import { useApi } from '@/lib/api/ApiContext'
import { ApiError } from '@/lib/api/client'
import { problemMessage } from '@/lib/api/problems'
import type { AiReplyDraft, AiTriageSuggestion, TicketView } from '@/lib/api/types'
import { useAiAvailable } from './api'
import { AiOutput } from './AiOutput'

/** A reply built from (and citing) knowledge-base articles, as opposed to a "nothing found" message. */
function isDraft(reply: AiReplyDraft): boolean {
  return reply.grounded && reply.citations.length > 0
}

/**
 * D13: the AI only proposes. Triage is applied by an agent through the ordinary ticket update; a draft lands in the
 * composer unsent. `onDraft` is null when the viewer can't reply, which hides Draft reply.
 */
export function TicketAiCard({
  ticket,
  onDraft,
}: {
  ticket: TicketView
  onDraft: ((draft: string) => void) | null
}) {
  const available = useAiAvailable()
  const api = useApi()
  const queryClient = useQueryClient()
  const canManage = useCan()(PERMISSIONS.ticketManage)
  const [busy, setBusy] = useState<'triage' | 'draft' | 'apply' | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [suggestion, setSuggestion] = useState<AiTriageSuggestion | null>(null)
  const [sources, setSources] = useState<AiReplyDraft | null>(null)
  const path = `/ai/helpdesk/tickets/${ticket.id}`

  if (!available) return null

  async function suggest() {
    setBusy('triage')
    setError(null)
    try {
      setSuggestion(await api.post<AiTriageSuggestion>(`${path}/triage`))
    } catch (e) {
      setError(problemMessage(e))
    } finally {
      setBusy(null)
    }
  }

  async function draft() {
    setBusy('draft')
    setError(null)
    try {
      const reply = await api.post<AiReplyDraft>(`${path}/reply-draft`)
      setSources(reply)
      // only a reply built from articles goes in the composer; a "nothing found" message never does
      if (isDraft(reply)) onDraft?.(reply.draft)
    } catch (e) {
      setError(problemMessage(e))
    } finally {
      setBusy(null)
    }
  }

  async function apply(s: AiTriageSuggestion) {
    setBusy('apply')
    setError(null)
    try {
      const saved = await api.put<TicketView>(`/helpdesk/tickets/${ticket.id}`, {
        subject: ticket.subject,
        description: ticket.description,
        requesterId: ticket.requester?.id ?? null,
        productId: ticket.product?.id ?? null,
        linkedType: ticket.linked?.type ?? null,
        linkedId: ticket.linked?.id ?? null,
        categoryId: s.categoryId ?? ticket.category?.id ?? null,
        priority: s.priority,
        channel: ticket.channel,
        version: ticket.version,
      })
      queryClient.setQueryData(['helpdesk', 'ticket', saved.id], saved)
      await invalidateHelpDesk(queryClient)
      toast.success('Triage applied.')
      setSuggestion(null)
    } catch (e) {
      // a conflict means the ticket moved on: reload it so the next attempt carries the current version
      if (e instanceof ApiError && e.status === 409)
        void queryClient.invalidateQueries({ queryKey: ['helpdesk', 'ticket', ticket.id] })
      setError(problemMessage(e))
    } finally {
      setBusy(null)
    }
  }

  return (
    <Card role="region" aria-label="AI assist">
      <CardHeader>
        <CardTitle>AI assist</CardTitle>
      </CardHeader>
      <CardContent className="space-y-3 text-sm">
        <div className="flex flex-wrap gap-2">
          <Button
            variant="outline"
            size="sm"
            disabled={busy !== null}
            onClick={() => void suggest()}
          >
            Suggest triage
          </Button>
          {onDraft && (
            <Button
              variant="outline"
              size="sm"
              disabled={busy !== null}
              onClick={() => void draft()}
            >
              Draft reply
            </Button>
          )}
        </div>
        <FormError message={error} />
        {suggestion && (
          <div className="space-y-2">
            <AiOutput
              body={suggestion.reason}
              extra={
                <dl className="grid grid-cols-[5rem_1fr] gap-x-3 gap-y-1">
                  <dt className="text-muted-foreground">Category</dt>
                  <dd>{suggestion.categoryName ?? 'No matching category'}</dd>
                  <dt className="text-muted-foreground">Priority</dt>
                  <dd>{PRIORITY_LABELS[suggestion.priority]}</dd>
                </dl>
              }
            />
            {canManage && (
              <Button size="sm" disabled={busy !== null} onClick={() => void apply(suggestion)}>
                Apply
              </Button>
            )}
          </div>
        )}
        {sources && isDraft(sources) && (
          <div className="space-y-1">
            <p className="text-muted-foreground">
              The draft is in the reply box below. Edit it before you send.
            </p>
            <AiOutput body={sources.draft} citations={sources.citations} grounded />
          </div>
        )}
        {sources && !isDraft(sources) && sources.citations.length === 0 && (
          <p role="status" className="text-muted-foreground">
            No matching knowledge-base article found — nothing was added to your reply.
          </p>
        )}
        {sources && !isDraft(sources) && sources.citations.length > 0 && (
          <div className="space-y-1">
            <p role="status" className="text-muted-foreground">
              No article answers this clearly — nothing was added to your reply.
            </p>
            <AiOutput body={sources.draft} citations={sources.citations} grounded />
          </div>
        )}
      </CardContent>
    </Card>
  )
}
