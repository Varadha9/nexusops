import { screen, waitFor } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { anAiSettings } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server).on('GET /ai/settings', { body: anAiSettings() })
  return { ...renderApp({ server, path: '/app/settings/ai' }), server }
}

describe('AiSettingsPage', () => {
  it('shows the toggle, limit, usage, provider and indexed passages', async () => {
    setup()
    expect(await screen.findByRole('switch', { name: 'AI assistance is on' })).toBeChecked()
    expect(screen.getByLabelText('Monthly request limit')).toHaveValue('500')
    expect(screen.getByText('12 of 500')).toBeInTheDocument()
    expect(screen.getByRole('progressbar')).toHaveAttribute('aria-valuenow', '12')
    expect(screen.getByText('LOCAL')).toBeInTheDocument()
    expect(screen.getByText('42')).toBeInTheDocument()
  })

  it('saves with the version', async () => {
    const { server, user } = setup()
    const limit = await screen.findByLabelText('Monthly request limit')
    server.on('PUT /ai/settings', (req) => ({
      body: anAiSettings({ ...(req.body as object), version: 4 }),
    }))
    await user.click(screen.getByRole('switch'))
    await user.clear(limit)
    await user.type(limit, '800')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText('AI settings saved.')).toBeInTheDocument()
    expect(server.callsTo('PUT /ai/settings')[0].body).toEqual({
      enabled: false,
      monthlyLimit: 800,
      version: 3,
    })
  })

  it('shows a server field error under the limit', async () => {
    const { server, user } = setup()
    const limit = await screen.findByLabelText('Monthly request limit')
    server.on('PUT /ai/settings', {
      status: 400,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'monthlyLimit', message: 'Enter a limit from 1 to 100000.' }],
      },
    })
    await user.clear(limit)
    await user.type(limit, '100000')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByText('Enter a limit from 1 to 100000.')).toBeInTheDocument()
    expect(limit).toHaveAttribute('aria-invalid', 'true')
  })

  it('rebuilds the index and reports what was indexed', async () => {
    const { server, user } = setup()
    const button = await screen.findByRole('button', { name: 'Rebuild index' })
    server.on('POST /ai/index/rebuild', { body: { indexedSources: 7, indexedChunks: 31 } })
    await user.click(button)
    expect(await screen.findByText('Indexed 7 documents (31 passages).')).toBeInTheDocument()
  })

  it('keeps the message and the edits after a stale-version conflict', async () => {
    const { server, user } = setup()
    const limit = await screen.findByLabelText('Monthly request limit')
    server.on('PUT /ai/settings', {
      status: 409,
      body: { detail: 'This was changed by someone else. Reload and try again.' },
    })
    server.on('GET /ai/settings', { body: anAiSettings({ version: 4 }) })
    await user.clear(limit)
    await user.type(limit, '900')
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    expect(await screen.findByRole('alert')).toHaveTextContent(
      'This was changed by someone else. Reload and try again.',
    )
    await waitFor(() => expect(server.callsTo('GET /ai/settings').length).toBeGreaterThan(1))
    await screen.findByText('12 of 500')
    expect(screen.getByRole('alert')).toHaveTextContent('changed by someone else')
    expect(screen.getByLabelText('Monthly request limit')).toHaveValue('900')
    // the retry carries the version the refetch returned
    server.on('PUT /ai/settings', (req) => ({ body: anAiSettings(req.body as object) }))
    await user.click(screen.getByRole('button', { name: 'Save changes' }))
    await waitFor(() => expect(server.callsTo('PUT /ai/settings')).toHaveLength(2))
    expect(server.callsTo('PUT /ai/settings')[1].body).toMatchObject({
      monthlyLimit: 900,
      version: 4,
    })
  })

  it('shows no access without the AI settings permission', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ permissions: ['tenant.settings.read'] }))
    renderApp({ server, path: '/app/settings/ai' })
    expect(await screen.findByText("You don't have access to this page")).toBeInTheDocument()
    expect(screen.queryByRole('switch')).not.toBeInTheDocument()
    expect(server.callsTo('GET /ai/settings')).toHaveLength(0)
  })
})
