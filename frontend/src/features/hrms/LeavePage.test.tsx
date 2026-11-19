import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { TenantRoot } from '@/app/TenantRoot'
import { todayIso } from '@/lib/format'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aLeaveRequest, anAway, anEmployeeSummary, defaultLeaveTypes, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'
import { LeaveRequestDialog } from './LeaveRequestDialog'

/** Today plus `days`, as YYYY-MM-DD in local time (the page compares against the local date). */
function inDays(days: number): string {
  const [y, m, d] = todayIso().split('-').map(Number)
  const later = new Date(y, m - 1, d + days)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${later.getFullYear()}-${pad(later.getMonth() + 1)}-${pad(later.getDate())}`
}

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'] }))
    .on('GET /hr/leave-requests', (r) => ({
      body:
        r.query.get('status') === 'PENDING'
          ? pageOf([aLeaveRequest()])
          : pageOf([
              aLeaveRequest(),
              aLeaveRequest({
                id: 'lr-2',
                status: 'APPROVED',
                startDate: '2027-02-01',
                endDate: '2027-02-01',
                days: 1,
              }),
            ]),
    }))
    .on('GET /hr/leave/away', { body: [anAway()] })
    .on('GET /hr/leave-types', { body: defaultLeaveTypes() })
    .on('GET /hr/employees', { body: pageOf([anEmployeeSummary()]) })
  return renderApp({ server, path: '/app/hrms/leave' })
}

describe('LeavePage', () => {
  it('shows requests waiting for a decision, all requests and who is away', async () => {
    setup()
    const queue = await screen.findByRole('region', { name: 'Waiting for a decision' })
    expect(await within(queue).findByText('Meera Iyer')).toBeInTheDocument()
    expect(queue).toHaveTextContent('2 days')
    const all = screen.getByRole('region', { name: 'All requests' })
    // the Status filter also lists "Approved": look in the table only
    const table = await within(all).findByRole('table')
    expect(await within(table).findByText('Approved')).toBeInTheDocument()
    const away = screen.getByRole('region', { name: "Who's away" })
    expect(await within(away).findByRole('link', { name: 'Meera Iyer' })).toBeInTheDocument()
  })

  it('approves with a note', async () => {
    const { server, user } = setup()
    const queue = await screen.findByRole('region', { name: 'Waiting for a decision' })
    const approve = await within(queue).findByRole('button', {
      name: 'Approve Meera Iyer 2027-03-01',
    })
    server.on('POST /hr/leave-requests/:id/approve', {
      body: aLeaveRequest({ status: 'APPROVED', version: 1 }),
    })
    server.on('GET /hr/leave-requests', { body: pageOf([]) })
    await user.click(approve)
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Note'), 'Enjoy')
    await user.click(within(dialog).getByRole('button', { name: 'Approve' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/leave-requests/:id/approve')[0]?.body).toEqual({
        note: 'Enjoy',
        version: 0,
      }),
    )
    expect(await within(queue).findByText('Nothing is waiting for a decision.')).toBeInTheDocument()
  })

  it('shows why a decision was refused', async () => {
    const { server, user } = setup()
    const queue = await screen.findByRole('region', { name: 'Waiting for a decision' })
    const reject = await within(queue).findByRole('button', {
      name: 'Reject Meera Iyer 2027-03-01',
    })
    server.on('POST /hr/leave-requests/:id/reject', {
      status: 409,
      body: { detail: 'Someone else must decide your own request.' },
    })
    await user.click(reject)
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Reject' }))
    expect(
      await within(dialog).findByText('Someone else must decide your own request.'),
    ).toBeInTheDocument()
  })

  it('records leave for someone', async () => {
    const { server, user } = setup()
    server.on('POST /hr/leave-requests', { status: 201, body: aLeaveRequest({ id: 'lr-new' }) })
    await user.click(await screen.findByRole('button', { name: 'Request leave for someone' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Employee'), 'e-meera')
    await user.selectOptions(within(dialog).getByLabelText('Leave type'), 'lt-annual')
    await user.type(within(dialog).getByLabelText('From'), '2027-03-01')
    await user.type(within(dialog).getByLabelText('To'), '2027-03-02')
    await user.type(within(dialog).getByLabelText('Reason'), 'Family')
    await user.click(within(dialog).getByRole('button', { name: 'Request leave' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/leave-requests')[0]?.body).toEqual({
        employeeId: 'e-meera',
        typeId: 'lt-annual',
        startDate: '2027-03-01',
        endDate: '2027-03-02',
        reason: 'Family',
      }),
    )
  })

  it('shows balance and overlap refusals in the dialog', async () => {
    const { server, user } = setup()
    server.on('POST /hr/leave-requests', {
      status: 409,
      body: { detail: 'Not enough leave left: 2.5 days available.' },
    })
    await user.click(await screen.findByRole('button', { name: 'Request leave for someone' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Employee'), 'e-meera')
    await user.selectOptions(within(dialog).getByLabelText('Leave type'), 'lt-annual')
    await user.type(within(dialog).getByLabelText('From'), '2027-03-01')
    await user.type(within(dialog).getByLabelText('To'), '2027-03-05')
    await user.click(within(dialog).getByRole('button', { name: 'Request leave' }))
    expect(
      await within(dialog).findByText('Not enough leave left: 2.5 days available.'),
    ).toBeInTheDocument()
  })

  it('pages through a long approval queue', async () => {
    const { server, user } = setup()
    server.on('GET /hr/leave-requests', (r) => ({
      body:
        r.query.get('status') === 'PENDING'
          ? pageOf(
              [aLeaveRequest({ id: `lr-p${r.query.get('page') ?? '0'}` })],
              45,
              Number(r.query.get('page') ?? 0),
            )
          : pageOf([]),
    }))
    const queue = await screen.findByRole('region', { name: 'Waiting for a decision' })
    const pages = await within(queue).findByRole('navigation', { name: 'Pagination' })
    expect(pages).toHaveTextContent('Page 1 of 3')
    await user.click(within(pages).getByRole('button', { name: 'Next page' }))
    await waitFor(() =>
      expect(
        server
          .callsTo('GET /hr/leave-requests')
          .some((c) => c.query.get('status') === 'PENDING' && c.query.get('page') === '1'),
      ).toBe(true),
    )
    expect(await within(queue).findByText('Page 2 of 3')).toBeInTheDocument()
  })

  it('asks for both dates when one is cleared in who is away', async () => {
    const { user } = setup()
    const away = await screen.findByRole('region', { name: "Who's away" })
    expect(await within(away).findByRole('link', { name: 'Meera Iyer' })).toBeInTheDocument()
    await user.clear(within(away).getByLabelText('From'))
    expect(await within(away).findByText('Choose both dates.')).toBeInTheDocument()
  })

  it('cancels upcoming leave after confirming', async () => {
    const { server, user } = setup()
    const start = inDays(30)
    server.on('GET /hr/leave-requests', (r) => ({
      body:
        r.query.get('status') === 'PENDING'
          ? pageOf([])
          : pageOf([
              aLeaveRequest({
                id: 'lr-later',
                status: 'APPROVED',
                startDate: start,
                endDate: inDays(31),
                version: 3,
              }),
            ]),
    }))
    const all = await screen.findByRole('region', { name: 'All requests' })
    const cancel = await within(all).findByRole('button', { name: `Cancel Meera Iyer ${start}` })
    server.on('POST /hr/leave-requests/:id/cancel', {
      body: aLeaveRequest({ id: 'lr-later', status: 'CANCELLED', version: 4 }),
    })
    await user.click(cancel)
    const dialog = await screen.findByRole('dialog')
    expect(dialog).toHaveTextContent('Their days go back to the balance.')
    await user.click(within(dialog).getByRole('button', { name: 'Cancel leave' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/leave-requests/:id/cancel')).toHaveLength(1),
    )
    const call = server.callsTo('POST /hr/leave-requests/:id/cancel')[0]
    expect(call.params.id).toBe('lr-later')
    expect(call.body).toEqual({ version: 3 })
  })

  it('refuses an end before the start before calling the server', async () => {
    const { server, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'Request leave for someone' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Employee'), 'e-meera')
    await user.selectOptions(within(dialog).getByLabelText('Leave type'), 'lt-annual')
    await user.type(within(dialog).getByLabelText('From'), '2027-03-05')
    await user.type(within(dialog).getByLabelText('To'), '2027-03-01')
    await user.click(within(dialog).getByRole('button', { name: 'Request leave' }))
    expect(
      await within(dialog).findByText("The end date can't be before the start date."),
    ).toBeInTheDocument()
    expect(server.callsTo('POST /hr/leave-requests')).toHaveLength(0)
  })
})

describe('LeaveRequestDialog for myself', () => {
  it('posts to the self-service endpoint without an employee', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['HRMS'], permissions: ['hr.self.service'] })).on(
      'GET /hr/leave-types',
      { body: defaultLeaveTypes() },
    )
    server.on('POST /hr/me/leave-requests', { status: 201, body: aLeaveRequest({ id: 'lr-mine' }) })
    const onSaved = vi.fn()
    const { user } = renderApp({
      server,
      path: '/request',
      routes: [
        {
          element: <TenantRoot />,
          children: [
            {
              path: '/request',
              element: <LeaveRequestDialog self onClose={() => {}} onSaved={onSaved} />,
            },
          ],
        },
      ],
    })
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).queryByLabelText('Employee')).not.toBeInTheDocument()
    await user.selectOptions(await within(dialog).findByLabelText('Leave type'), 'lt-annual')
    await user.type(within(dialog).getByLabelText('From'), '2027-03-01')
    await user.type(within(dialog).getByLabelText('To'), '2027-03-02')
    await user.type(within(dialog).getByLabelText('Reason'), '  Family  ')
    await user.click(within(dialog).getByRole('button', { name: 'Request leave' }))
    await waitFor(() => expect(onSaved).toHaveBeenCalled())
    expect(server.callsTo('POST /hr/me/leave-requests')[0]?.body).toEqual({
      typeId: 'lt-annual',
      startDate: '2027-03-01',
      endDate: '2027-03-02',
      reason: 'Family',
    })
    expect(server.callsTo('POST /hr/leave-requests')).toHaveLength(0)
  })
})
