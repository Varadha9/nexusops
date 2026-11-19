import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aLeaveBalance,
  aLeaveRequest,
  anEmployee,
  anOnboardingTemplate,
  aSkillGap,
  defaultLeaveTypes,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'] }))
    .on('GET /hr/employees/:id', { body: anEmployee() })
    .on('GET /hr/employees/:id/skill-gap', { body: aSkillGap() })
    .on('GET /hr/employees/:id/leave-balances', {
      body: [
        aLeaveBalance(),
        aLeaveBalance({
          type: { id: 'lt-unpaid', name: 'Unpaid leave' },
          paid: false,
          entitlement: null,
          available: null,
          taken: 1,
          pending: 0,
        }),
      ],
    })
    .on('GET /hr/leave-requests', { body: pageOf([aLeaveRequest()]) })
    .on('GET /hr/leave-types', { body: defaultLeaveTypes() })
    .on('GET /hr/onboarding-templates', { body: [anOnboardingTemplate()] })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path: '/app/hrms/employees/e-meera' })
}

describe('LeaveCard', () => {
  it("shows the year's balances and requests", async () => {
    const { server } = setup()
    const card = await screen.findByRole('region', { name: 'Leave' })
    const annual = (await within(card).findByText('Annual leave')).closest('tr') as HTMLElement
    expect(annual).toHaveTextContent('20 days')
    expect(annual).toHaveTextContent('14 days')
    const unpaid = within(card).getByText('Unpaid leave').closest('tr') as HTMLElement
    expect(unpaid).toHaveTextContent('Unlimited')
    // "Pending" is also a balance column: look in the request list only
    const requests = await within(card).findByRole('list', { name: 'Leave requests' })
    expect(within(requests).getByText('Pending')).toBeInTheDocument()
    expect(server.callsTo('GET /hr/leave-requests')[0].query.get('employeeId')).toBe('e-meera')
  })

  it('adjusts a balance with a reason', async () => {
    const { server, user } = setup()
    const card = await screen.findByRole('region', { name: 'Leave' })
    const adjust = await within(card).findByRole('button', { name: 'Adjust balance' })
    server.on('POST /hr/employees/:id/leave-adjustments', {
      body: [aLeaveBalance({ entitlement: 22.5 })],
    })
    await user.click(adjust)
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Leave type'), 'lt-annual')
    await user.type(
      within(dialog).getByLabelText('Days (use a minus sign to take days away)'),
      '2.5',
    )
    await user.type(within(dialog).getByLabelText('Reason'), 'Carry-over agreed')
    await user.click(within(dialog).getByRole('button', { name: 'Adjust' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/employees/:id/leave-adjustments')[0]?.body).toMatchObject({
        typeId: 'lt-annual',
        days: 2.5,
        reason: 'Carry-over agreed',
      }),
    )
  })
})
