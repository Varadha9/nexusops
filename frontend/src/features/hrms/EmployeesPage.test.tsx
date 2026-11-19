import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aDepartment,
  aJobRole,
  anEmployee,
  anEmployeeSummary,
  anOnboardingTemplate,
  aSkillGap,
  aSummary,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/hrms/employees', permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'], ...(permissions ? { permissions } : {}) }))
    .on('GET /hr/employees', {
      body: pageOf([
        anEmployeeSummary({
          id: 'e-arun',
          name: 'Arun Shah',
          employeeNumber: 'O-1',
          department: null,
          jobRole: null,
        }),
        anEmployeeSummary({ manager: { id: 'e-ravi', name: 'Ravi Kumar', employeeNumber: 'M-1' } }),
      ]),
    })
    .on('GET /hr/departments', { body: [aDepartment()] })
    .on('GET /hr/job-roles', { body: [aJobRole()] })
    .on('GET /hr/onboarding-templates', { body: [anOnboardingTemplate()] })
    .on('GET /hr/members', {
      body: [{ id: 'u-meera', name: 'Meera Iyer', email: 'meera@deccan.test' }],
    })
    .on('GET /parties', {
      body: pageOf([aSummary({ id: 'p-meera', kind: 'PERSON', name: 'Meera Iyer' })]),
    })
    // the employee page opens after a hire
    .on('GET /hr/employees/:id', { body: anEmployee({ id: 'e-new' }) })
    .on('GET /hr/employees/:id/skill-gap', { body: aSkillGap() })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path })
}

describe('EmployeesPage', () => {
  it('lists current employees with department, role and manager', async () => {
    setup()
    const row = (await screen.findByRole('link', { name: 'Meera Iyer' })).closest(
      'tr',
    ) as HTMLElement
    expect(row).toHaveTextContent('E-1')
    expect(row).toHaveTextContent('Sales')
    expect(row).toHaveTextContent('Account executive')
    expect(row).toHaveTextContent('Ravi Kumar')
    expect(within(row).getByText('Active')).toBeInTheDocument()
    expect(screen.getByRole('link', { name: 'Meera Iyer' })).toHaveAttribute(
      'href',
      '/app/hrms/employees/e-meera',
    )
  })

  it('filters by status, department, role and search', async () => {
    const { server, user } = setup()
    await screen.findByRole('link', { name: 'Meera Iyer' })
    expect(server.callsTo('GET /hr/employees')[0].query.get('status')).toBeNull()
    await user.selectOptions(screen.getByLabelText('Show'), 'LEFT')
    await user.selectOptions(screen.getByLabelText('Department'), 'd-sales')
    await user.selectOptions(screen.getByLabelText('Job role'), 'jr-exec')
    await user.type(screen.getByLabelText('Search employees'), 'E-1')
    await user.click(screen.getByRole('button', { name: 'Search' }))
    await waitFor(() => {
      const q = server.callsTo('GET /hr/employees').at(-1)?.query
      expect(q?.get('status')).toBe('LEFT')
      expect(q?.get('departmentId')).toBe('d-sales')
      expect(q?.get('jobRoleId')).toBe('jr-exec')
      expect(q?.get('q')).toBe('E-1')
    })
  })

  it('hires a new person with onboarding and opens their page', async () => {
    const { server, user, router } = setup()
    server.on('POST /hr/employees', { status: 201, body: anEmployee({ id: 'e-new' }) })
    await user.click(await screen.findByRole('button', { name: 'Hire employee' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByLabelText('New person'))
    await user.type(within(dialog).getByLabelText('First name'), 'Zoe')
    await user.type(within(dialog).getByLabelText('Last name'), 'Nair')
    await user.type(within(dialog).getByLabelText('Email'), 'zoe@deccan.test')
    await user.type(within(dialog).getByLabelText('Employee number'), 'N-1')
    await user.selectOptions(within(dialog).getByLabelText('Department'), 'd-sales')
    await user.selectOptions(within(dialog).getByLabelText('Job role'), 'jr-exec')
    await user.selectOptions(within(dialog).getByLabelText('Employment type'), 'CONTRACT')
    await user.type(within(dialog).getByLabelText('Start date'), '2026-11-02')
    await user.selectOptions(within(dialog).getByLabelText('Team member account'), 'u-meera')
    await user.selectOptions(within(dialog).getByLabelText('Onboarding'), 'ot-std')
    await user.click(within(dialog).getByRole('button', { name: 'Hire' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/employees')[0]?.body).toEqual({
        personId: null,
        person: { firstName: 'Zoe', lastName: 'Nair', email: 'zoe@deccan.test', phone: null },
        employeeNumber: 'N-1',
        departmentId: 'd-sales',
        jobRoleId: 'jr-exec',
        managerId: null,
        employmentType: 'CONTRACT',
        startDate: '2026-11-02',
        userId: 'u-meera',
        onboardingTemplateId: 'ot-std',
      }),
    )
    await waitFor(() => expect(router.state.location.pathname).toBe('/app/hrms/employees/e-new'))
  })

  it('hires an existing person', async () => {
    const { server, user } = setup()
    server.on('POST /hr/employees', { status: 201, body: anEmployee({ id: 'e-new' }) })
    await user.click(await screen.findByRole('button', { name: 'Hire employee' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Person'), 'p-meera')
    await user.type(within(dialog).getByLabelText('Employee number'), 'E-9')
    await user.type(within(dialog).getByLabelText('Start date'), '2026-11-02')
    await user.click(within(dialog).getByRole('button', { name: 'Hire' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/employees')[0]?.body).toMatchObject({
        personId: 'p-meera',
        person: null,
        employmentType: 'FULL_TIME',
        onboardingTemplateId: null,
      }),
    )
  })

  it('needs a person, a number and a start date', async () => {
    const { server, user } = setup()
    await user.click(await screen.findByRole('button', { name: 'Hire employee' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Hire' }))
    expect(await within(dialog).findByText('Choose a person.')).toBeInTheDocument()
    expect(within(dialog).getByText('Enter a start date.')).toBeInTheDocument()
    expect(within(dialog).getAllByText('Required.').length).toBeGreaterThanOrEqual(1)
    expect(server.callsTo('POST /hr/employees')).toHaveLength(0)
  })

  it('shows a taken number from the server on its field', async () => {
    const { server, user } = setup()
    server.on('POST /hr/employees', {
      status: 409,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'employeeNumber', message: 'Another employee already has this number.' }],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Hire employee' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Person'), 'p-meera')
    await user.type(within(dialog).getByLabelText('Employee number'), 'E-1')
    await user.type(within(dialog).getByLabelText('Start date'), '2026-11-02')
    await user.click(within(dialog).getByRole('button', { name: 'Hire' }))
    expect(
      await within(dialog).findByText('Another employee already has this number.'),
    ).toBeInTheDocument()
  })

  it("shows a bad onboarding template and a new person's email error on their fields", async () => {
    const { server, user } = setup()
    server.on('POST /hr/employees', {
      status: 400,
      body: {
        detail: 'Request validation failed.',
        errors: [
          { field: 'onboardingTemplateId', message: 'Choose an active onboarding template.' },
          { field: 'email', message: 'Enter a valid email address.' },
        ],
      },
    })
    await user.click(await screen.findByRole('button', { name: 'Hire employee' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByLabelText('New person'))
    await user.type(within(dialog).getByLabelText('First name'), 'Zoe')
    await user.type(within(dialog).getByLabelText('Last name'), 'Nair')
    await user.type(within(dialog).getByLabelText('Employee number'), 'N-1')
    await user.type(within(dialog).getByLabelText('Start date'), '2026-11-02')
    await user.click(within(dialog).getByRole('button', { name: 'Hire' }))
    expect(
      await within(dialog).findByText('Choose an active onboarding template.'),
    ).toBeInTheDocument()
    expect(within(dialog).getByText('Enter a valid email address.')).toBeInTheDocument()
  })

  it('hides Hire employee from readers', async () => {
    setup('/app/hrms/employees', ['hr.employee.read'])
    await screen.findByRole('link', { name: 'Meera Iyer' })
    expect(screen.queryByRole('button', { name: 'Hire employee' })).not.toBeInTheDocument()
  })
})
