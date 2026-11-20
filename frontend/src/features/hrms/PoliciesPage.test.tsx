import { act, screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aPolicy, aPolicySummary, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/hrms/policies', permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'], ...(permissions ? { permissions } : {}) }))
    .on('GET /hr/policies', {
      body: pageOf([
        aPolicySummary(),
        aPolicySummary({
          id: 'pol-2',
          title: 'Travel policy',
          status: 'DRAFT',
          publishedAt: null,
        }),
      ]),
    })
    .on('GET /hr/policies/:id', { body: aPolicy({ id: 'pol-new', status: 'DRAFT' }) })
  return renderApp({ server, path })
}

describe('PoliciesPage', () => {
  it('lists policies with status and excerpt', async () => {
    setup()
    const first = (await screen.findByRole('link', { name: 'Leave policy' })).closest(
      'li',
    ) as HTMLElement
    expect(first).toHaveTextContent('Annual leave is 20 days a year.')
    const draft = screen.getByRole('link', { name: 'Travel policy' }).closest('li') as HTMLElement
    expect(within(draft).getByText('Draft')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Leave policy' })).toHaveAttribute(
      'href',
      '/app/hrms/policies/pol-1',
    )
  })

  it("shows the URL's search words in the search box", async () => {
    const { router } = setup('/app/hrms/policies?q=leave')
    expect(await screen.findByLabelText('Search policies')).toHaveValue('leave')
    await act(() => router.navigate('/app/hrms/policies?q=travel'))
    await waitFor(() => expect(screen.getByLabelText('Search policies')).toHaveValue('travel'))
  })

  it('searches and filters by status', async () => {
    const { server, user } = setup()
    await screen.findByRole('link', { name: 'Leave policy' })
    await user.selectOptions(screen.getByLabelText('Status'), 'ARCHIVED')
    await user.type(screen.getByLabelText('Search policies'), 'annual leave')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => {
      const last = server.callsTo('GET /hr/policies').at(-1)?.query
      expect(last?.get('status')).toBe('ARCHIVED')
      expect(last?.get('q')).toBe('annual leave')
    })
  })

  it('writes a draft and opens it', async () => {
    const { server, user, router } = setup()
    server.on('POST /hr/policies', {
      status: 201,
      body: aPolicy({ id: 'pol-new', status: 'DRAFT' }),
    })
    await user.click(await screen.findByRole('button', { name: 'New policy' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Title'), 'Leave policy')
    await user.type(within(dialog).getByLabelText('Policy'), 'Annual leave is 20 days a year.')
    await user.click(within(dialog).getByRole('button', { name: 'Save draft' }))
    await waitFor(() => expect(server.callsTo('POST /hr/policies')).toHaveLength(1))
    expect(server.callsTo('POST /hr/policies')[0].body).toEqual({
      title: 'Leave policy',
      body: 'Annual leave is 20 days a year.',
    })
    await waitFor(() => expect(router.state.location.pathname).toBe('/app/hrms/policies/pol-new'))
  })

  it('needs a title and a text', async () => {
    const { server, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'New policy' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Save draft' }))
    expect(await within(dialog).findAllByText('Required.')).toHaveLength(2)
    expect(server.callsTo('POST /hr/policies')).toHaveLength(0)
  })

  it('shows staff published policies only, without status filter or New policy', async () => {
    setup('/app/hrms/policies', ['hr.self.service'])
    await screen.findByRole('link', { name: 'Leave policy' })
    expect(screen.queryByLabelText('Status')).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'New policy' })).not.toBeInTheDocument()
  })
})
