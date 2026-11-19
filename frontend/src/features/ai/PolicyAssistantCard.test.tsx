import { act, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aMe, aPolicySummary, defaultLeaveTypes, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(status = { enabled: true, provider: 'LOCAL' }) {
  const server = fakeServer()
  signedIn(
    server,
    testProfile({ modules: ['HRMS'], permissions: ['hr.self.service', 'ai.assistant.use'] }),
  )
    .on('GET /hr/me', { body: aMe() })
    .on('GET /hr/leave-types', { body: defaultLeaveTypes() })
    .on('GET /hr/policies', { body: pageOf([aPolicySummary()]) })
    .on('GET /ai/status', { body: status })
  return { ...renderApp({ server, path: '/app/hrms/me' }), server }
}

describe('PolicyAssistantCard', () => {
  it('is hidden when AI is turned off', async () => {
    const { queryClient } = setup({ enabled: false, provider: 'LOCAL' })
    await screen.findByRole('heading', { name: 'My HR' })
    // wait until the status response has been stored and rendered, then check the card stayed away
    await waitFor(() => expect(queryClient.getQueryData(['ai', 'status'])).toBeDefined())
    await act(async () => {})
    expect(screen.queryByRole('region', { name: 'Ask about HR policies' })).not.toBeInTheDocument()
  })

  it('asks a short question without calling the server', async () => {
    const { server, user } = setup()
    const card = await screen.findByRole('region', { name: 'Ask about HR policies' })
    await user.type(within(card).getByLabelText('Your question'), 'hi')
    await user.click(within(card).getByRole('button', { name: 'Ask' }))
    expect(await within(card).findByText('Ask a question of 3 to 500 characters.')).toBeVisible()
    expect(server.callsTo('POST /ai/hr/policy-questions')).toHaveLength(0)
  })

  it('shows the answer with the cited policies', async () => {
    const { server, user } = setup()
    server.on('POST /ai/hr/policy-questions', {
      body: {
        answer: 'Annual leave is 20 days a year.',
        citations: [{ sourceType: 'HR_POLICY', sourceId: 'pol-1', title: 'Leave policy' }],
        mode: 'LOCAL',
      },
    })
    const card = await screen.findByRole('region', { name: 'Ask about HR policies' })
    await user.type(within(card).getByLabelText('Your question'), 'How much leave do I get?')
    await user.click(within(card).getByRole('button', { name: 'Ask' }))
    expect(await within(card).findByText('Annual leave is 20 days a year.')).toBeInTheDocument()
    expect(server.callsTo('POST /ai/hr/policy-questions')[0].body).toEqual({
      question: 'How much leave do I get?',
    })
    expect(within(card).getByText('AI-generated — check before use')).toBeInTheDocument()
    expect(within(card).getByText("Answered from your workspace's documents")).toBeInTheDocument()
    expect(within(card).getByRole('link', { name: 'Leave policy' })).toHaveAttribute(
      'href',
      '/app/hrms/policies/pol-1',
    )
  })

  it('shows the server message when the request fails', async () => {
    const { server, user } = setup()
    server.on('POST /ai/hr/policy-questions', {
      status: 503,
      body: { detail: 'The AI service is unavailable. Try again shortly.' },
    })
    const card = await screen.findByRole('region', { name: 'Ask about HR policies' })
    await user.type(within(card).getByLabelText('Your question'), 'How much leave do I get?')
    await user.click(within(card).getByRole('button', { name: 'Ask' }))
    expect(await within(card).findByRole('alert')).toHaveTextContent(
      'The AI service is unavailable. Try again shortly.',
    )
  })
})
