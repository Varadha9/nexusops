import { screen, waitFor, within } from '@testing-library/react'
import { describe, expect, it } from 'vitest'
import { fakeServer } from '@/test/fakeServer'
import { signedIn, testProfile } from '@/test/fixtures'
import { aCourse, anEmployeeSummary, aSkill, aTrainingAssignment, pageOf } from '@/test/records'
import { renderApp } from '@/test/renderApp'

function setup(path = '/app/hrms/training', permissions?: string[]) {
  const server = fakeServer()
  signedIn(server, testProfile({ modules: ['HRMS'], ...(permissions ? { permissions } : {}) }))
    .on('GET /hr/courses', { body: [aCourse()] })
    .on('GET /hr/training-assignments', {
      body: pageOf([
        aTrainingAssignment(),
        aTrainingAssignment({ id: 'ta-2', overdue: true, dueOn: '2026-09-01' }),
      ]),
    })
    .on('GET /hr/skills', { body: [aSkill()] })
    .on('GET /hr/employees', { body: pageOf([anEmployeeSummary()]) })
  return renderApp({ server, path })
}

describe('TrainingPage', () => {
  it('lists courses with the skills they teach, and assignments with overdue ones marked', async () => {
    setup()
    const courses = await screen.findByRole('region', { name: 'Courses' })
    expect(await within(courses).findByText('Advanced negotiation')).toBeInTheDocument()
    expect(courses).toHaveTextContent('Negotiation (4)')
    expect(courses).toHaveTextContent('3.5 h')
    const assignments = screen.getByRole('region', { name: 'Assignments' })
    expect(await within(assignments).findByText('Overdue')).toBeInTheDocument()
  })

  it('reads the overdue filter from the URL', async () => {
    const { server } = setup('/app/hrms/training?overdue=true')
    await screen.findByRole('region', { name: 'Assignments' })
    await waitFor(() =>
      expect(server.callsTo('GET /hr/training-assignments')[0]?.query.get('overdue')).toBe('true'),
    )
  })

  it('creates a course that teaches a skill', async () => {
    const { server, user } = setup()
    server.on('POST /hr/courses', { status: 201, body: aCourse({ id: 'c-new' }) })
    await user.click(await screen.findByRole('button', { name: 'New course' }))
    const dialog = await screen.findByRole('dialog')
    await user.type(within(dialog).getByLabelText('Title'), 'Negotiation basics')
    await user.type(within(dialog).getByLabelText('Duration (hours)'), '2')
    await user.type(within(dialog).getByLabelText('Link'), 'https://learn.example/basics')
    await user.click(within(dialog).getByRole('button', { name: 'Add skill' }))
    await user.selectOptions(within(dialog).getByLabelText('Skill 1'), 's-neg')
    await user.selectOptions(within(dialog).getByLabelText('Skill 1 level'), '3')
    await user.click(within(dialog).getByRole('button', { name: 'Create course' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/courses')[0]?.body).toEqual({
        title: 'Negotiation basics',
        description: null,
        durationHours: 2,
        link: 'https://learn.example/basics',
        skills: [{ skillId: 's-neg', level: 3 }],
      }),
    )
  })

  it('assigns, completes and cancels training', async () => {
    const { server, user } = setup()
    server.on('POST /hr/training-assignments', {
      status: 201,
      body: aTrainingAssignment({ id: 'ta-new' }),
    })
    await user.click(await screen.findByRole('button', { name: 'Assign training' }))
    const dialog = await screen.findByRole('dialog')
    await user.selectOptions(within(dialog).getByLabelText('Course'), 'c-adv')
    await user.selectOptions(within(dialog).getByLabelText('Employee'), 'e-meera')
    await user.type(within(dialog).getByLabelText('Due'), '2027-01-31')
    await user.click(within(dialog).getByRole('button', { name: 'Assign' }))
    await waitFor(() =>
      expect(server.callsTo('POST /hr/training-assignments')[0]?.body).toEqual({
        courseId: 'c-adv',
        employeeId: 'e-meera',
        dueOn: '2027-01-31',
      }),
    )
    const assignments = screen.getByRole('region', { name: 'Assignments' })
    const complete = (
      await within(assignments).findAllByRole('button', { name: /^Mark complete/ })
    )[0]
    server.on('POST /hr/training-assignments/:id/complete', {
      body: aTrainingAssignment({ status: 'COMPLETED', completedOn: '2026-10-09', version: 1 }),
    })
    await user.click(complete)
    await waitFor(() =>
      expect(server.callsTo('POST /hr/training-assignments/:id/complete')[0]?.body).toEqual({
        note: null,
        version: 0,
      }),
    )
  })

  it('cancels an assignment after confirmation', async () => {
    const { server, user } = setup()
    const assignments = await screen.findByRole('region', { name: 'Assignments' })
    const cancel = (await within(assignments).findAllByRole('button', { name: /^Cancel/ }))[0]
    server.on('POST /hr/training-assignments/:id/cancel', {
      body: aTrainingAssignment({ status: 'CANCELLED', version: 1 }),
    })
    await user.click(cancel)
    await user.click(
      within(await screen.findByRole('dialog')).getByRole('button', { name: 'Cancel training' }),
    )
    await waitFor(() =>
      expect(server.callsTo('POST /hr/training-assignments/:id/cancel')[0]?.body).toEqual({
        version: 0,
      }),
    )
  })

  it('shows readers no training actions', async () => {
    setup('/app/hrms/training', ['hr.employee.read'])
    await screen.findByRole('region', { name: 'Courses' })
    expect(screen.queryByRole('button', { name: 'New course' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Assign training' })).not.toBeInTheDocument()
  })
})
