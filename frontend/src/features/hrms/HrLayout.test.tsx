import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { ALL_TENANT_PERMISSIONS } from '@/features/auth/permissions'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { anHrDashboard } from '@/test/records'
import { renderApp } from '@/test/renderApp'

describe('HrLayout', () => {
  it('explains that HRMS is off when the module is disabled', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: [] }))
    renderApp({ server, path: '/app/hrms' })
    expect(await screen.findByText('HRMS is not enabled for this workspace.')).toBeInTheDocument()
  })

  it('shows the sections the user may open', async () => {
    const server = fakeServer()
    signedIn(server, testProfile({ modules: ['HRMS'] })).on('GET /hr/dashboard', {
      body: anHrDashboard(),
    })
    renderApp({ server, path: '/app/hrms' })
    const nav = await screen.findByRole('navigation', { name: 'HRMS' })
    expect(nav).toHaveTextContent('Overview')
    expect(nav).toHaveTextContent('Employees')
    expect(nav).toHaveTextContent('Leave')
    expect(nav).toHaveTextContent('Training')
    expect(nav).toHaveTextContent('Policies')
    expect(nav).toHaveTextContent('My HR')
  })

  it('has no sections without any HRMS permission', async () => {
    const server = fakeServer()
    signedIn(
      server,
      testProfile({
        modules: ['HRMS'],
        permissions: [...ALL_TENANT_PERMISSIONS].filter((p) => !p.startsWith('hr.')),
      }),
    )
    renderApp({ server, path: '/app/hrms' })
    expect(await screen.findByText("You don't have access to this page")).toBeInTheDocument()
  })
})
