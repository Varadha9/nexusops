import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aLeaveRequest, anAway, anEmployeeSummary, defaultLeaveTypes, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

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
