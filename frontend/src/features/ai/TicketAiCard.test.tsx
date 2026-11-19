import { act, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  anAgent,
  anArticle,
  anArticleSummary,
  aMessage,
  aPerson,
  aTicket,
  aTicketContext,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'
import { PERMISSIONS } from '@/features/auth/permissions'
import type { TicketView } from '@/lib/api/types'

const TRIAGE = {
  categoryId: 'c-hardware',
  categoryName: 'Hardware',
  priority: 'HIGH',
  reason: 'Mentions a jammed printer.',
  mode: 'LOCAL',
}

function setup({
  ticket = aTicket({ version: 4 }),
  status = { enabled: true, provider: 'LOCAL' },
  permissions,
}: {
  ticket?: TicketView
  status?: { enabled: boolean; provider: string }
  permissions?: string[]
} = {}) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HELPDESK'], ...(permissions ? { permissions } : {}) }))
    .on('GET /helpdesk/tickets/:id', { body: ticket })
    .on('GET /helpdesk/tickets/:id/messages', { body: [aMessage()] })
    .on('GET /helpdesk/tickets/:id/context', { body: aTicketContext() })
    .on('GET /parties/:id', { body: aPerson({ id: 'p-meera', name: 'Meera Iyer' }) })
    .on('GET /helpdesk/articles/:id', { body: anArticle() })
    .on('GET /helpdesk/articles', { body: pageOf([anArticleSummary()]) })
    .on('GET /helpdesk/agents', { body: [anAgent()] })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /ai/status', { body: status })
  return { ...renderApp({ server, path: `/app/helpdesk/tickets/${ticket.id}` }), server }
}

describe('TicketAiCard', () => {
  it('is hidden when AI is turned off for the workspace', async () => {
    const { queryClient } = setup({ status: { enabled: false, provider: 'LOCAL' } })
    await screen.findByRole('region', { name: 'Details' })
    // wait until the status response has been stored and rendered, then check the card stayed away
    await waitFor(() => expect(queryClient.getQueryData(['ai', 'status'])).toBeDefined())
    await act(async () => {})
    expect(screen.queryByRole('region', { name: 'AI assist' })).not.toBeInTheDocument()
  })

  it('is hidden without the AI permission, and does not ask the server', async () => {
    const { server } = setup({
      permissions: [PERMISSIONS.ticketRead, PERMISSIONS.ticketManage],
    })
    await screen.findByRole('region', { name: 'Details' })
    expect(screen.queryByRole('region', { name: 'AI assist' })).not.toBeInTheDocument()
    expect(server.callsTo('GET /ai/status')).toHaveLength(0)
  })

  it('suggests triage and labels it as AI output', async () => {
    const { server, user } = setup()
    server.on('POST /ai/helpdesk/tickets/:id/triage', { body: TRIAGE })
    const card = await screen.findByRole('region', { name: 'AI assist' })
    await user.click(within(card).getByRole('button', { name: 'Suggest triage' }))
    expect(await within(card).findByText('Hardware')).toBeInTheDocument()
    expect(within(card).getByText('High')).toBeInTheDocument()
    expect(within(card).getByText('Mentions a jammed printer.')).toBeInTheDocument()
    expect(within(card).getByText('AI-generated — check before use')).toBeInTheDocument()
  })

  it('applies the suggestion through the ticket update, with the version, then refetches', async () => {
    const { server, user } = setup({
      ticket: aTicket({
        version: 4,
        linked: { type: 'SALES_ORDER', id: 'so-1', label: 'SO-00001' },
      }),
    })
    server.on('POST /ai/helpdesk/tickets/:id/triage', { body: TRIAGE })
    const card = await screen.findByRole('region', { name: 'AI assist' })
    await user.click(within(card).getByRole('button', { name: 'Suggest triage' }))
    const apply = await within(card).findByRole('button', { name: 'Apply' })
    const updated = aTicket({
      version: 5,
      priority: 'HIGH',
      category: { id: 'c-hardware', name: 'Hardware' },
    })
    server.on('PUT /helpdesk/tickets/:id', { body: updated }).on('GET /helpdesk/tickets/:id', {
      body: updated,
    })
    await user.click(apply)
    await waitFor(() => expect(server.callsTo('PUT /helpdesk/tickets/:id')).toHaveLength(1))
    expect(server.callsTo('PUT /helpdesk/tickets/:id')[0].body).toEqual({
      subject: 'Printer jams on every page',
      description: 'Since Monday the printer jams on every page.',
      requesterId: 'p-meera',
      productId: 'pr-widget',
      linkedType: 'SALES_ORDER',
      linkedId: 'so-1',
      categoryId: 'c-hardware',
      priority: 'HIGH',
      channel: 'PHONE',
      version: 4,
    })
    await waitFor(() =>
      expect(server.callsTo('GET /helpdesk/tickets/:id').length).toBeGreaterThan(1),
    )
    await waitFor(() =>
      expect(screen.queryByRole('button', { name: 'Apply' })).not.toBeInTheDocument(),
    )
  })

  it('shows "No matching category" and applies only the priority', async () => {
    const { server, user } = setup()
    server.on('POST /ai/helpdesk/tickets/:id/triage', {
      body: { ...TRIAGE, categoryId: null, categoryName: null },
    })
    const card = await screen.findByRole('region', { name: 'AI assist' })
    await user.click(within(card).getByRole('button', { name: 'Suggest triage' }))
    expect(await within(card).findByText('No matching category')).toBeInTheDocument()
    server.on('PUT /helpdesk/tickets/:id', { body: aTicket({ version: 5, priority: 'HIGH' }) })
    await user.click(within(card).getByRole('button', { name: 'Apply' }))
    await waitFor(() => expect(server.callsTo('PUT /helpdesk/tickets/:id')).toHaveLength(1))
    expect(server.callsTo('PUT /helpdesk/tickets/:id')[0].body).toMatchObject({
      categoryId: 'c-general',
      priority: 'HIGH',
      version: 4,
    })
  })

  it('puts a drafted reply in the composer and lists the cited articles', async () => {
    const { server, user } = setup()
    server.on('POST /ai/helpdesk/tickets/:id/reply-draft', {
      body: {
        draft: 'Hello Meera,\nPlease clear the paper tray.',
        citations: [{ sourceType: 'KB_ARTICLE', sourceId: 'a-1', title: 'Clearing paper jams' }],
        mode: 'LOCAL',
      },
    })
    const card = await screen.findByRole('region', { name: 'AI assist' })
    await user.click(within(card).getByRole('button', { name: 'Draft reply' }))
    await waitFor(() =>
      expect(screen.getByLabelText('Message')).toHaveValue(
        'Hello Meera,\nPlease clear the paper tray.',
      ),
    )
    expect(within(card).getByText("Answered from your workspace's documents")).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'Clearing paper jams' })).toHaveAttribute(
      'href',
      '/app/helpdesk/articles/a-1',
    )
  })

  it.each([
    [409, 'AI assistance is turned off for this workspace.'],
    [429, 'This workspace has used its AI requests for this month.'],
    [503, 'The AI service is unavailable. Try again shortly.'],
  ])('shows the server message for a %i', async (status, detail) => {
    const { server, user } = setup()
    server.on('POST /ai/helpdesk/tickets/:id/triage', { status, body: { detail } })
    const card = await screen.findByRole('region', { name: 'AI assist' })
    await user.click(within(card).getByRole('button', { name: 'Suggest triage' }))
    expect(await within(card).findByRole('alert')).toHaveTextContent(detail)
  })
})
