import { screen } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { anEmployeeSummary, aPerson, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(modules: string[], employees = [anEmployeeSummary()]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules }))
    .on('GET /parties/:id', { body: aPerson({ id: 'p-meera', name: 'Meera Iyer' }) })
    .on('GET /parties', { body: pageOf([]) })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
    .on('GET /hr/employees', { body: pageOf(employees) })
  return renderApp({ server, path: '/app/directory/p-meera' })
}

describe('EmployeeRecordLink', () => {
  it("links a person's directory page to their employee record", async () => {
    const { server } = setup(['HRMS'])
    expect(await screen.findByRole('link', { name: 'View employee record' })).toHaveAttribute(
      'href',
      '/app/hrms/employees/e-meera',
    )
    expect(server.callsTo('GET /hr/employees')[0].query.get('personId')).toBe('p-meera')
  })

  it('is absent without HRMS or without a record', async () => {
    setup([])
    await screen.findByRole('heading', { name: 'Meera Iyer' })
    expect(screen.queryByRole('link', { name: 'View employee record' })).not.toBeInTheDocument()
  })
})
