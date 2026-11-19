import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aPolicy } from '@/test/records'
import { renderApp } from '@/test/renderApp'
import type { PolicyView } from '@/lib/api/types'

function setup(policy: PolicyView = aPolicy(), permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'], ...(permissions ? { permissions } : {}) })).on(
    'GET /hr/policies/:id',
    { body: policy },
  )
  return renderApp({ server, path: `/app/hrms/policies/${policy.id}` })
}

describe('PolicyDetailPage', () => {
  it('shows the policy', async () => {
    setup()
    expect(await screen.findByRole('heading', { name: 'Leave policy' })).toBeInTheDocument()
    expect(screen.getByText('Annual leave is 20 days a year.')).toBeInTheDocument()
    expect(screen.getByText('Published')).toBeInTheDocument()
  })

  it('publishes a draft', async () => {
    const { server, user } = setup(aPolicy({ status: 'DRAFT', publishedAt: null, version: 0 }))
    const published = aPolicy({ version: 1 })
    const publish = await screen.findByRole('button', { name: 'Publish' })
    server
      .on('POST /hr/policies/:id/publish', { body: published })
      .on('GET /hr/policies/:id', { body: published })
    await user.click(publish)
    await waitFor(() =>
      expect(server.callsTo('POST /hr/policies/:id/publish')[0]?.body).toEqual({ version: 0 }),
    )
    expect(await screen.findByRole('button', { name: 'Unpublish' })).toBeInTheDocument()
  })

  it('unpublishes a published policy', async () => {
    const { server, user } = setup()
    const draft = aPolicy({ status: 'DRAFT', publishedAt: null, version: 2 })
    const unpublish = await screen.findByRole('button', { name: 'Unpublish' })
    server
      .on('POST /hr/policies/:id/unpublish', { body: draft })
      .on('GET /hr/policies/:id', { body: draft })
    await user.click(unpublish)
    await waitFor(() =>
      expect(server.callsTo('POST /hr/policies/:id/unpublish')[0]?.body).toEqual({ version: 1 }),
    )
    expect(await screen.findByRole('button', { name: 'Publish' })).toBeInTheDocument()
  })

  it('archives after confirmation, and an archived policy has no actions', async () => {
    const { server, user } = setup()
    const archived = aPolicy({ status: 'ARCHIVED', version: 2 })
    const archive = await screen.findByRole('button', { name: 'Archive' })
    server
      .on('POST /hr/policies/:id/archive', { body: archived })
      .on('GET /hr/policies/:id', { body: archived })
    await user.click(archive)
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Archive policy' }),
    )
    await waitFor(() =>
      expect(server.callsTo('POST /hr/policies/:id/archive')[0]?.body).toEqual({ version: 1 }),
    )
    expect(await screen.findByText('Archived')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Publish' })).not.toBeInTheDocument()
  })

  it('edits the policy', async () => {
    const { server, user } = setup()
    const edited = aPolicy({ title: 'Annual leave policy', version: 2 })
    const edit = await screen.findByRole('button', { name: 'Edit' })
    server.on('PUT /hr/policies/:id', { body: edited }).on('GET /hr/policies/:id', { body: edited })
    await user.click(edit)
    const dialog = await screen.findByRole('dialog')
    const title = within(dialog).getByLabelText('Title')
    await user.clear(title)
    await user.type(title, 'Annual leave policy')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    await waitFor(() =>
      expect(server.callsTo('PUT /hr/policies/:id')[0]?.body).toEqual({
        title: 'Annual leave policy',
        body: 'Annual leave is 20 days a year.',
        version: 1,
      }),
    )
    expect(await screen.findByRole('heading', { name: 'Annual leave policy' })).toBeInTheDocument()
  })

  it('shows staff no actions', async () => {
    setup(aPolicy(), ['hr.self.service'])
    await screen.findByRole('heading', { name: 'Leave policy' })
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Unpublish' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Archive' })).not.toBeInTheDocument()
  })
})
