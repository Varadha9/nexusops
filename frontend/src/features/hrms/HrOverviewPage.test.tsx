import { screen, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { anHrDashboard } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(dashboard = anHrDashboard()) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'] })).on('GET /hr/dashboard', { body: dashboard })
  return renderApp({ server, path: '/app/hrms' })
}

describe('HrOverviewPage', () => {
  it('shows headcount, movement and what needs attention', async () => {
    setup()
    const people = await screen.findByRole('region', { name: 'People' })
    expect(people).toHaveTextContent('Active employees: 12')
    expect(within(people).getByText('Sales')).toBeInTheDocument()
    expect(within(people).getByText('No department')).toBeInTheDocument()
    const attention = screen.getByRole('region', { name: 'Needs attention' })
    expect(within(attention).getByRole('link', { name: /Pending leave requests/ })).toHaveAttribute(
      'href',
      '/app/hrms/leave',
    )
    expect(within(attention).getByRole('link', { name: /Overdue trainings/ })).toHaveAttribute(
      'href',
      '/app/hrms/training?overdue=true',
    )
    const away = screen.getByRole('region', { name: 'Away today' })
    expect(within(away).getByRole('link', { name: 'Meera Iyer' })).toHaveAttribute(
      'href',
      '/app/hrms/employees/e-meera',
    )
    const gaps = screen.getByRole('region', { name: 'Most common skill gaps' })
    expect(within(gaps).getByText('Negotiation')).toBeInTheDocument()
  })

  it('says when nobody is away', async () => {
    setup(anHrDashboard({ awayToday: [], awayNext7Days: [] }))
    const away = await screen.findByRole('region', { name: 'Away today' })
    expect(within(away).getByText('Nobody is away today.')).toBeInTheDocument()
  })
})
