import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aDepartment,
  aHoliday,
  aJobRole,
  anOnboardingTemplate,
  aSkill,
  defaultLeaveTypes,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup() {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'] }))
    .on('GET /hr/departments', (r) => ({
      body: r.query.get('archived') === 'true' ? [] : [aDepartment()],
    }))
    .on('GET /hr/skills', (r) => ({
      body:
        r.query.get('archived') === 'true'
          ? []
          : [aSkill({ id: 's-excel', name: 'Excel' }), aSkill()],
    }))
    .on('GET /hr/job-roles', (r) => ({
      body: r.query.get('archived') === 'true' ? [] : [aJobRole()],
    }))
    .on('GET /hr/leave-types', (r) => ({
      body: r.query.get('archived') === 'true' ? [] : defaultLeaveTypes(),
    }))
    .on('GET /hr/holidays', { body: [aHoliday()] })
    .on('GET /hr/onboarding-templates', (r) => ({
      body: r.query.get('archived') === 'true' ? [] : [anOnboardingTemplate()],
    }))
  return renderApp({ server, path: '/app/settings/hrms' })
}

describe('HrSettingsPage', () => {
  it('lists every HR setting', async () => {
    setup()
    const departments = await screen.findByRole('region', { name: 'Departments' })
    expect(await within(departments).findByText('Sales')).toBeInTheDocument()
    const skills = screen.getByRole('region', { name: 'Skills' })
    expect(await within(skills).findByText('Excel')).toBeInTheDocument()
    const roles = screen.getByRole('region', { name: 'Job roles' })
    expect(await within(roles).findByText('Account executive')).toBeInTheDocument()
    expect(roles).toHaveTextContent('Negotiation (4)')
    const leave = screen.getByRole('region', { name: 'Leave types' })
    expect(await within(leave).findByText('Unpaid leave')).toBeInTheDocument()
    expect(leave).toHaveTextContent('Unlimited')
    const holidays = screen.getByRole('region', { name: 'Public holidays' })
    expect(await within(holidays).findByText('Republic Day')).toBeInTheDocument()
    const templates = screen.getByRole('region', { name: 'Onboarding templates' })
    expect(await within(templates).findByText('Standard onboarding')).toBeInTheDocument()
  })

  it('creates a job role with required skills', async () => {
    const { server, user } = setup()
    server.on('POST /hr/job-roles', { status: 201, body: aJobRole({ id: 'jr-new' }) })
    const roles = await screen.findByRole('region', { name: 'Job roles' })
    await user.click(within(roles).getByRole('button', { name: 'New job role' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Title'), 'Analyst')
    await user.selectOptions(within(dialog).getByLabelText('Department'), 'd-sales')
    await user.click(within(dialog).getByRole('button', { name: 'Add skill' }))
    await user.selectOptions(within(dialog).getByLabelText('Skill 1'), 's-excel')
    await user.selectOptions(within(dialog).getByLabelText('Skill 1 minimum level'), '3')
    await user.click(within(dialog).getByRole('button', { name: 'Create job role' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/job-roles')[0]?.body).toEqual({
        title: 'Analyst',
        departmentId: 'd-sales',
        description: null,
        requiredSkills: [{ skillId: 's-excel', minimumLevel: 3 }],
      }),
    )
  })

  it('adds a public holiday and removes one', async () => {
    const { server, user } = setup()
    server.on('POST /hr/holidays', {
      status: 201,
      body: aHoliday({ id: 'h-2', date: '2027-08-15', name: 'Independence Day' }),
    })
    server.on('DELETE /hr/holidays/:id', { status: 204 })
    const holidays = await screen.findByRole('region', { name: 'Public holidays' })
    await user.type(within(holidays).getByLabelText('Date'), '2027-08-15')
    await user.type(within(holidays).getByLabelText('Holiday name'), 'Independence Day')
    await user.click(within(holidays).getByRole('button', { name: 'Add holiday' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/holidays')[0]?.body).toEqual({
        date: '2027-08-15',
        name: 'Independence Day',
      }),
    )
    await user.click(within(holidays).getByRole('button', { name: 'Remove Republic Day' }))
    await waitFor(() => expect(server.callsTo('DELETE /hr/holidays/:id')[0]?.params.id).toBe('h-1'))
  })

  it('creates a leave type with an allowance or unlimited', async () => {
    const { server, user } = setup()
    server.on('POST /hr/leave-types', { status: 201, body: defaultLeaveTypes()[0] })
    const leave = await screen.findByRole('region', { name: 'Leave types' })
    await user.click(within(leave).getByRole('button', { name: 'New leave type' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Study leave')
    await user.click(within(dialog).getByLabelText('Paid'))
    await user.click(within(dialog).getByRole('button', { name: 'Create leave type' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/leave-types')[0]?.body).toEqual({
        name: 'Study leave',
        paid: true,
        annualAllowance: null,
      }),
    )
  })

  it('edits an onboarding template item by item', async () => {
    const { server, user } = setup()
    const sections = await screen.findByRole('region', { name: 'Onboarding templates' })
    server.on('PUT /hr/onboarding-templates/:id', { body: anOnboardingTemplate({ version: 1 }) })
    await user.click(
      await within(sections).findByRole('button', { name: 'Edit Standard onboarding' }),
    )
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Remove item 2' }))
    await user.click(within(dialog).getByRole('button', { name: 'Add item' }))
    await user.type(within(dialog).getByLabelText('Item 2 title'), 'Meet the team')
    await user.selectOptions(within(dialog).getByLabelText('Item 2 owner'), 'MANAGER')
    const offset = within(dialog).getByLabelText('Item 2 due (days after start)')
    await user.clear(offset)
    await user.type(offset, '2')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    await waitFor(() =>
      expect(server.callsTo('PUT /hr/onboarding-templates/:id')[0]?.body).toEqual({
        name: 'Standard onboarding',
        items: [
          {
            title: 'Prepare laptop and accounts',
            description: null,
            owner: 'HR',
            dueOffsetDays: 0,
          },
          { title: 'Meet the team', description: null, owner: 'MANAGER', dueOffsetDays: 2 },
        ],
        version: 0,
      }),
    )
  })

  it('archives a department after confirmation and restores an archived skill', async () => {
    const { server, user } = setup()
    server.on('GET /hr/skills', (r) => ({
      body:
        r.query.get('archived') === 'true'
          ? [aSkill({ id: 's-old', name: 'Fax', archivedAt: '2026-10-01T00:00:00Z' })]
          : [aSkill()],
    }))
    server.on('POST /hr/departments/:id/archive', { body: aDepartment() })
    server.on('POST /hr/skills/:id/restore', { body: aSkill({ id: 's-old' }) })
    await user.click(await screen.findByRole('button', { name: 'Archive Sales' }))
    const confirm = await screen.findByRole('dialog')
    expect(confirm).toHaveTextContent("Departments with employees in them can't be archived.")
    await user.click(within(confirm).getByRole('button', { name: 'Archive' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/departments/:id/archive')[0]?.params.id).toBe('d-sales'),
    )
    await user.click(await screen.findByRole('button', { name: 'Restore Fax' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/skills/:id/restore')[0]?.params.id).toBe('s-old'),
    )
  })

  it('edits a job role with its version and keeps its required skills', async () => {
    const { server, user } = setup()
    server.on('PUT /hr/job-roles/:id', { body: aJobRole({ version: 1 }) })
    const roles = await screen.findByRole('region', { name: 'Job roles' })
    await user.click(await within(roles).findByRole('button', { name: 'Edit Account executive' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByLabelText('Skill 1 minimum level')).toHaveValue('4')
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    await waitFor(() =>
      expect(server.callsTo('PUT /hr/job-roles/:id')[0]?.body).toEqual({
        title: 'Account executive',
        departmentId: 'd-sales',
        description: null,
        requiredSkills: [{ skillId: 's-neg', minimumLevel: 4 }],
        version: 0,
      }),
    )
  })

  it('asks for a skill and a level, and refuses a repeated skill, before sending', async () => {
    const { server, user } = setup()
    const roles = await screen.findByRole('region', { name: 'Job roles' })
    await user.click(within(roles).getByRole('button', { name: 'New job role' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Title'), 'Analyst')
    await user.click(within(dialog).getByRole('button', { name: 'Add skill' }))
    await user.click(within(dialog).getByRole('button', { name: 'Create job role' }))
    expect(await within(dialog).findByText('Choose a skill.')).toBeInTheDocument()
    expect(within(dialog).getByText('Choose a level.')).toBeInTheDocument()

    await user.click(within(dialog).getByRole('button', { name: 'Add skill' }))
    await within(dialog).findByLabelText('Skill 2')
    await user.selectOptions(await within(dialog).findByLabelText('Skill 1'), 's-excel')
    await user.selectOptions(within(dialog).getByLabelText('Skill 1 minimum level'), '2')
    await user.selectOptions(within(dialog).getByLabelText('Skill 2'), 's-excel')
    await user.selectOptions(within(dialog).getByLabelText('Skill 2 minimum level'), '3')
    await user.click(within(dialog).getByRole('button', { name: 'Create job role' }))
    expect(await within(dialog).findByText('Each skill can be listed once.')).toBeInTheDocument()
    expect(server.callsTo('POST /hr/job-roles')).toHaveLength(0)
  })

  it('shows a server error on the skill row it belongs to', async () => {
    const { server, user } = setup()
    server.on('POST /hr/job-roles', {
      status: 400,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'requiredSkills[0].skillId', message: 'Choose a skill that is active.' }],
      },
    })
    const roles = await screen.findByRole('region', { name: 'Job roles' })
    await user.click(within(roles).getByRole('button', { name: 'New job role' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Title'), 'Analyst')
    await user.click(within(dialog).getByRole('button', { name: 'Add skill' }))
    await user.selectOptions(await within(dialog).findByLabelText('Skill 1'), 's-excel')
    await user.selectOptions(within(dialog).getByLabelText('Skill 1 minimum level'), '3')
    await user.click(within(dialog).getByRole('button', { name: 'Create job role' }))
    expect(await within(dialog).findByText('Choose a skill that is active.')).toBeInTheDocument()
  })

  it('edits a leave type to unlimited', async () => {
    const { server, user } = setup()
    server.on('PUT /hr/leave-types/:id', { body: defaultLeaveTypes()[0] })
    const leave = await screen.findByRole('region', { name: 'Leave types' })
    await user.click(await within(leave).findByRole('button', { name: 'Edit Annual leave' }))
    const dialog = await screen.findByRole('dialog')
    const allowance = within(dialog).getByLabelText('Annual allowance (days)')
    expect(allowance).toHaveValue('20')
    await user.clear(allowance)
    await user.click(within(dialog).getByRole('button', { name: 'Save changes' }))
    await waitFor(() =>
      expect(server.callsTo('PUT /hr/leave-types/:id')[0]?.body).toEqual({
        name: 'Annual leave',
        paid: true,
        annualAllowance: null,
        version: 0,
      }),
    )
  })

  it('needs at least one onboarding item', async () => {
    const { server, user } = setup()
    const sections = await screen.findByRole('region', { name: 'Onboarding templates' })
    await user.click(within(sections).getByRole('button', { name: 'New template' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Name'), 'Interns')
    await user.click(within(dialog).getByRole('button', { name: 'Create template' }))
    expect(await within(dialog).findByText('Add at least one item.')).toBeInTheDocument()
    expect(server.callsTo('POST /hr/onboarding-templates')).toHaveLength(0)
  })
})
