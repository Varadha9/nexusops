import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aLeaveRequest,
  aMe,
  anEmployee,
  aPolicySummary,
  aTrainingAssignment,
  defaultLeaveTypes,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(me = aMe(), permissions = ['hr.self.service']) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'], permissions }))
    .on('GET /hr/me', { body: me })
    .on('GET /hr/leave-types', { body: defaultLeaveTypes() })
    .on('GET /hr/policies', { body: pageOf([aPolicySummary()]) })
  return renderApp({ server, path: '/app/hrms/me' })
}

describe('MyHrPage', () => {
  it('shows my balances, requests, trainings and the policies', async () => {
    setup()
    expect(await screen.findByRole('heading', { name: 'My HR' })).toBeInTheDocument()
    const balances = screen.getByRole('region', { name: 'My leave' })
    expect(balances).toHaveTextContent('Annual leave')
    expect(balances).toHaveTextContent('14 days')
    const trainings = screen.getByRole('region', { name: 'My training' })
    expect(trainings).toHaveTextContent('Advanced negotiation')
    expect(await screen.findByRole('link', { name: 'Leave policy' })).toHaveAttribute(
      'href',
      '/app/hrms/policies/pol-1',
    )
  })

  it('requests leave for myself', async () => {
    const { server, user } = setup()
    const request = await screen.findByRole('button', { name: 'Request leave' })
    server.on('POST /hr/me/leave-requests', { status: 201, body: aLeaveRequest({ id: 'lr-new' }) })
    await user.click(request)
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).queryByLabelText('Employee')).not.toBeInTheDocument()
    await user.selectOptions(within(dialog).getByLabelText('Leave type'), 'lt-annual')
    await user.type(within(dialog).getByLabelText('From'), '2027-03-01')
    await user.type(within(dialog).getByLabelText('To'), '2027-03-02')
    await user.click(within(dialog).getByRole('button', { name: 'Request leave' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/me/leave-requests')[0]?.body).toEqual({
        typeId: 'lt-annual',
        startDate: '2027-03-01',
        endDate: '2027-03-02',
        reason: null,
      }),
    )
  })

  it('cancels my pending request and completes my training', async () => {
    const { server, user } = setup()
    const cancel = await screen.findByRole('button', { name: 'Cancel leave 2027-03-01' })
    const updated = aMe({
      requests: [
        aLeaveRequest({ status: 'CANCELLED', cancelledAt: '2026-10-09T10:00:00Z', version: 1 }),
      ],
    })
    server
      .on('POST /hr/me/leave-requests/:id/cancel', { body: aLeaveRequest({ status: 'CANCELLED' }) })
      .on('GET /hr/me', { body: updated })
    await user.click(cancel)
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel leave' }),
    )
    await waitFor(() =>
      expect(server.callsTo('POST /hr/me/leave-requests/:id/cancel')[0]?.body).toEqual({
        version: 0,
      }),
    )
    // wait for the refetch to show the cancelled request before replacing the GET reply
    const requests = screen.getByRole('list', { name: 'My requests' })
    expect(await within(requests).findByText('Cancelled')).toBeInTheDocument()
    const complete = await screen.findByRole('button', {
      name: 'Mark Advanced negotiation complete',
    })
    server
      .on('POST /hr/me/training/:id/complete', {
        body: aTrainingAssignment({ status: 'COMPLETED' }),
      })
      .on('GET /hr/me', { body: aMe({ trainings: [] }) })
    await user.click(complete)
    // completing can't be undone: it asks first
    const confirm = await screen.findByRole('dialog')
    expect(confirm).toHaveTextContent("This raises your skills and can't be undone.")
    expect(server.callsTo('POST /hr/me/training/:id/complete')).toHaveLength(0)
    await user.click(within(confirm).getByRole('button', { name: 'Mark complete' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/me/training/:id/complete')[0]?.body).toEqual({
        note: null,
        version: 0,
      }),
    )
    const trainings = screen.getByRole('region', { name: 'My training' })
    expect(await within(trainings).findByText('No training assigned.')).toBeInTheDocument()
  })

  it('keeps a training open when the confirmation is dismissed', async () => {
    const { server, user } = setup()
    await user.click(
      await screen.findByRole('button', { name: 'Mark Advanced negotiation complete' }),
    )
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel' }),
    )
    await waitFor(() => expect(screen.queryByRole('dialog')).not.toBeInTheDocument())
    expect(server.callsTo('POST /hr/me/training/:id/complete')).toHaveLength(0)
  })

  it('links my onboarding tasks only when I can open tasks', async () => {
    const onboarding = aMe({
      employee: anEmployee({
        user: { id: 'u-ada', name: 'Ada Lovelace' },
        onboarding: { templateId: 'ot-1', templateName: 'Standard onboarding', done: 1, total: 3 },
      }),
    })
    const { unmount } = setup(onboarding)
    const section = await screen.findByRole('region', { name: 'My onboarding' })
    expect(section).toHaveTextContent('1 of 3 tasks done')
    expect(within(section).queryByRole('link', { name: 'See my tasks' })).not.toBeInTheDocument()
    expect(section).toHaveTextContent('Your manager or HR will tick these off.')
    unmount()
    setup(onboarding, ['hr.self.service', 'collaboration.task.read'])
    const withTasks = await screen.findByRole('region', { name: 'My onboarding' })
    expect(within(withTasks).getByRole('link', { name: 'See my tasks' })).toHaveAttribute(
      'href',
      '/app/tasks?assignee=me',
    )
  })

  it('explains an account that is not linked', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['HRMS'], permissions: ['hr.self.service'] }))
      .on('GET /hr/me', {
        status: 404,
        body: { detail: "Your account isn't linked to an employee record." },
      })
      .on('GET /hr/policies', { body: pageOf([]) })
    renderApp({ server, path: '/app/hrms/me' })
    expect(
      await screen.findByText("Your account isn't linked to an employee record."),
    ).toBeInTheDocument()
    expect(
      screen.getByText('Ask HR to link your account to your employee record.'),
    ).toBeInTheDocument()
  })
})
