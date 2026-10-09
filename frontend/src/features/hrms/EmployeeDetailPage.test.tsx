import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import {
  aDepartment,
  aJobRole,
  anEmployee,
  anOnboardingTemplate,
  aSkill,
  aSkillGap,
  aTrainingAssignment,
  pageOf,
} from '@/test/records'
import { renderApp } from '@/test/renderApp'
import type { EmployeeView } from '@/lib/api/types'

function setup(employee: EmployeeView = anEmployee(), permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'], ...(permissions ? { permissions } : {}) }))
    .on('GET /hr/employees/:id', { body: employee })
    .on('GET /hr/employees/:id/skill-gap', { body: aSkillGap() })
    .on('GET /hr/employees', { body: pageOf([]) })
    .on('GET /hr/departments', { body: [aDepartment()] })
    .on('GET /hr/job-roles', { body: [aJobRole()] })
    .on('GET /hr/skills', { body: [aSkill(), aSkill({ id: 's-excel', name: 'Excel' })] })
    .on('GET /hr/onboarding-templates', { body: [anOnboardingTemplate()] })
    .on('GET /hr/employees/:id/leave-balances', { body: [] })
    .on('GET /hr/leave-requests', { body: pageOf([]) })
    .on('GET /hr/leave-types', { body: [] })
    .on('GET /hr/training-assignments', { body: pageOf([aTrainingAssignment({ overdue: true })]) })
    .on('GET /hr/members', { body: [] })
    .on('GET /activities', { body: pageOf([]) })
    .on('GET /documents', { body: [] })
  return renderApp({ server, path: `/app/hrms/employees/${employee.id}` })
}

describe('EmployeeDetailPage', () => {
  it('lists the open training with an overdue mark, and offers to assign more', async () => {
    const { server } = setup()
    const training = await screen.findByRole('region', { name: 'Training' })
    expect(await within(training).findByText('Advanced negotiation')).toBeInTheDocument()
    expect(within(training).getByText('Overdue')).toBeInTheDocument()
    expect(within(training).getByRole('button', { name: 'Assign training' })).toBeInTheDocument()
    const call = server.callsTo('GET /hr/training-assignments')[0]
    expect(call?.query.get('employeeId')).toBe('e-meera')
    expect(call?.query.get('status')).toBe('ASSIGNED')
  })

  it('shows the person, the employment and their skills against the role', async () => {
    setup()
    expect(await screen.findByRole('heading', { name: 'Meera Iyer · E-1' })).toBeInTheDocument()
    const details = screen.getByRole('region', { name: 'Employment' })
    expect(details).toHaveTextContent('Sales')
    expect(details).toHaveTextContent('Account executive')
    expect(details).toHaveTextContent('Full time')
    expect(within(details).getByRole('link', { name: 'Meera Iyer' })).toHaveAttribute(
      'href',
      '/app/directory/p-meera',
    )
    const gap = screen.getByRole('region', { name: 'Skills and gaps' })
    expect(within(gap).getByText('Negotiation')).toBeInTheDocument()
    expect(await within(gap).findByText(/needs 4 · has 2/)).toBeInTheDocument()
    expect(within(gap).getByText('Advanced negotiation')).toBeInTheDocument()
  })

  it('edits the skills as a whole', async () => {
    const { server, user } = setup()
    const gap = await screen.findByRole('region', { name: 'Skills and gaps' })
    const updated = anEmployee({
      skills: [{ skillId: 's-neg', name: 'Negotiation', level: 4, note: null }],
      version: 1,
    })
    server
      .on('PUT /hr/employees/:id/skills', { body: updated })
      .on('GET /hr/employees/:id', { body: updated })
    await user.click(within(gap).getByRole('button', { name: 'Edit skills' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Skill 1 level'), '4')
    await user.click(within(dialog).getByRole('button', { name: 'Add skill' }))
    await user.selectOptions(within(dialog).getByLabelText('Skill 2'), 's-excel')
    await user.selectOptions(within(dialog).getByLabelText('Skill 2 level'), '3')
    await user.type(within(dialog).getByLabelText('Skill 2 note'), 'Self-taught')
    await user.click(within(dialog).getByRole('button', { name: 'Save skills' }))
    await waitFor(() =>
      expect(server.callsTo('PUT /hr/employees/:id/skills')[0]?.body).toEqual({
        skills: [
          { skillId: 's-neg', level: 4, note: null },
          { skillId: 's-excel', level: 3, note: 'Self-taught' },
        ],
        version: 0,
      }),
    )
  })

  it('refuses a skill listed twice and shows a row error from the server', async () => {
    const { server, user } = setup()
    const gap = await screen.findByRole('region', { name: 'Skills and gaps' })
    await user.click(within(gap).getByRole('button', { name: 'Edit skills' }))
    const dialog = await screen.findByRole('dialog')
    await within(dialog).findByRole('option', { name: 'Excel' })
    await user.click(within(dialog).getByRole('button', { name: 'Add skill' }))
    await user.selectOptions(within(dialog).getByLabelText('Skill 2'), 's-neg')
    await user.selectOptions(within(dialog).getByLabelText('Skill 2 level'), '3')
    await user.click(within(dialog).getByRole('button', { name: 'Save skills' }))
    expect(await within(dialog).findByText('Each skill can be listed once.')).toBeInTheDocument()
    expect(server.callsTo('PUT /hr/employees/:id/skills')).toHaveLength(0)

    server.on('PUT /hr/employees/:id/skills', {
      status: 400,
      body: {
        detail: 'Request validation failed.',
        errors: [{ field: 'skills[1].skillId', message: 'Choose an active skill.' }],
      },
    })
    await user.selectOptions(within(dialog).getByLabelText('Skill 2'), 's-excel')
    await user.click(within(dialog).getByRole('button', { name: 'Save skills' }))
    expect(await within(dialog).findByText('Choose an active skill.')).toBeInTheDocument()
  })

  it('ends and reinstates employment', async () => {
    const { server, user } = setup()
    const ended = anEmployee({ endDate: '2026-12-31', endReason: 'Resigned', version: 1 })
    await screen.findByRole('heading', { name: 'Meera Iyer · E-1' })
    server
      .on('POST /hr/employees/:id/end', { body: ended })
      .on('GET /hr/employees/:id', { body: ended })
    await user.click(screen.getByRole('button', { name: 'End employment' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Last day'), '2026-12-31')
    await user.type(within(dialog).getByLabelText('Reason'), 'Resigned')
    await user.click(within(dialog).getByRole('button', { name: 'End employment' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/employees/:id/end')[0]?.body).toEqual({
        endDate: '2026-12-31',
        reason: 'Resigned',
        version: 0,
      }),
    )
    expect(await screen.findByText(/Leaving on/)).toBeInTheDocument()
    const back = anEmployee({ version: 2 })
    const reinstate = await screen.findByRole('button', { name: 'Reinstate' })
    server
      .on('POST /hr/employees/:id/reinstate', { body: back })
      .on('GET /hr/employees/:id', { body: back })
    await user.click(reinstate)
    await waitFor(() =>
      expect(server.callsTo('POST /hr/employees/:id/reinstate')[0]?.body).toEqual({ version: 1 }),
    )
  })

  it('starts onboarding from a template and then shows progress', async () => {
    const { server, user } = setup()
    const onboarding = await screen.findByRole('region', { name: 'Onboarding' })
    const started = anEmployee({
      onboarding: { templateId: 'ot-std', templateName: 'Standard onboarding', done: 0, total: 2 },
      version: 1,
    })
    server
      .on('POST /hr/employees/:id/onboarding', { body: started })
      .on('GET /hr/employees/:id', { body: started })
    await within(onboarding).findByRole('option', { name: 'Standard onboarding' })
    await user.selectOptions(within(onboarding).getByLabelText('Template'), 'ot-std')
    await user.click(within(onboarding).getByRole('button', { name: 'Start onboarding' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/employees/:id/onboarding')[0]?.body).toEqual({
        templateId: 'ot-std',
      }),
    )
    expect(await within(onboarding).findByText('0 of 2 tasks done')).toBeInTheDocument()
  })

  it('shows readers no actions', async () => {
    setup(anEmployee(), ['hr.employee.read'])
    await screen.findByRole('heading', { name: 'Meera Iyer · E-1' })
    expect(screen.queryByRole('button', { name: 'End employment' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Edit skills' })).not.toBeInTheDocument()
  })
})
