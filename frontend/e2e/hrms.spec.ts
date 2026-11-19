import { expect, test, type Page } from '@playwright/test'
import { emailText, tokenFromEmail } from './support/mailpit'
import { newWorkspace, PASSWORD, signIn, signUpAndVerify } from './support/workspace'

function hrTab(page: Page, name: string) {
  return page.getByRole('navigation', { name: 'HRMS' }).getByRole('link', { name })
}

/** yyyy-mm-dd in UTC: the workspace's time zone (new workspaces are on UTC), whatever the runner's zone. */
function iso(d: Date): string {
  return d.toISOString().slice(0, 10)
}

/** The next Tuesday and Wednesday at least a day away (UTC): two working days in one calendar year and within 7 days. */
function nextTuesdayAndWednesday(): [string, string] {
  const d = new Date()
  d.setUTCDate(d.getUTCDate() + 1)
  while (d.getUTCDay() !== 2) d.setUTCDate(d.getUTCDate() + 1)
  const wed = new Date(d)
  wed.setUTCDate(d.getUTCDate() + 1)
  return [iso(d), iso(wed)]
}

test('a new hire is onboarded, closes a skill gap with training, and takes approved leave', async ({
  page,
  browser,
}) => {
  const ws = newWorkspace('hr')
  const hireEmail = `zoe-${ws.slug}@e2e.test`
  await signUpAndVerify(page, ws)
  await signIn(page, ws, ws.email)
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()
  const nav = page.getByRole('navigation', { name: 'Workspace' })

  // Enable HRMS; the new hire gets an account.
  await page.goto('/app/settings/modules')
  await page.getByRole('switch', { name: 'HRMS' }).click()
  await expect(page.getByText('HRMS enabled.')).toBeVisible()
  await page
    .getByRole('navigation', { name: 'Settings' })
    .getByRole('link', { name: 'Users' })
    .click()
  await page.getByRole('button', { name: 'Invite people' }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByLabel('Email').fill(hireEmail)
  await dialog.getByLabel('Role').selectOption({ label: 'TENANT_ADMIN' })
  await dialog.getByRole('button', { name: 'Send invitation' }).click()
  await expect(page.getByText(`Invitation sent to ${hireEmail}.`)).toBeVisible()
  const token = await tokenFromEmail(hireEmail, '/invite/accept')
  const hireContext = await browser.newContext()
  const hire = await hireContext.newPage()
  await hire.goto(`/invite/accept?token=${encodeURIComponent(token)}`)
  await hire.getByLabel('First name').fill('Zoe')
  await hire.getByLabel('Last name').fill('Nair')
  await hire.getByLabel('Password', { exact: true }).fill(PASSWORD)
  await hire.getByLabel('Repeat password', { exact: true }).fill(PASSWORD)
  await hire.getByRole('button', { name: `Join ${ws.name}` }).click()
  await expect(hire.getByRole('heading', { name: "You're in" })).toBeVisible()

  // A job role that needs Negotiation at level 4, and a course that teaches it.
  await page
    .getByRole('navigation', { name: 'Settings' })
    .getByRole('link', { name: 'HRMS' })
    .click()
  const skills = page.getByRole('region', { name: 'Skills' })
  await skills.getByRole('button', { name: 'New skill' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Name').fill('Negotiation')
  await dialog.getByRole('button', { name: 'Create skill' }).click()
  await expect(skills.getByText('Negotiation')).toBeVisible()
  const roles = page.getByRole('region', { name: 'Job roles' })
  await roles.getByRole('button', { name: 'New job role' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Title').fill('Account executive')
  await dialog.getByRole('button', { name: 'Add skill' }).click()
  await dialog.getByLabel('Skill 1', { exact: true }).selectOption({ label: 'Negotiation' })
  await dialog.getByLabel('Skill 1 minimum level').selectOption('4')
  await dialog.getByRole('button', { name: 'Create job role' }).click()
  await expect(roles.getByText('Negotiation (4)')).toBeVisible()

  // Ada is an employee too (she will manage Zoe).
  await nav.getByRole('link', { name: 'HRMS' }).click()
  await hrTab(page, 'Employees').click()
  await page.getByRole('button', { name: 'Hire employee' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('New person').check()
  await dialog.getByLabel('First name').fill('Ada')
  await dialog.getByLabel('Last name').fill('Owner')
  await dialog.getByLabel('Employee number').fill('E-1')
  await dialog.getByLabel('Start date').fill('2025-01-06')
  await dialog.getByLabel('Find a team member').fill('owner')
  await dialog.getByLabel('Team member account').selectOption({ label: 'Ada Owner' })
  await dialog.getByRole('button', { name: 'Hire' }).click()
  await expect(page.getByRole('heading', { name: 'Ada Owner · E-1' })).toBeVisible()

  // Zoe is hired today, reporting to Ada, with the standard onboarding.
  await hrTab(page, 'Employees').click()
  await page.getByRole('button', { name: 'Hire employee' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('New person').check()
  await dialog.getByLabel('First name').fill('Zoe')
  await dialog.getByLabel('Last name').fill('Nair')
  await dialog.getByLabel('Email').fill(hireEmail)
  await dialog.getByLabel('Employee number').fill('E-2')
  await dialog.getByLabel('Job role').selectOption({ label: 'Account executive' })
  await dialog.getByLabel('Start date').fill(iso(new Date()))
  await dialog.getByLabel('Find manager').fill('Ada')
  await dialog.getByLabel('Manager', { exact: true }).selectOption({ label: 'Ada Owner · E-1' })
  await dialog.getByLabel('Find a team member').fill('zoe')
  await dialog.getByLabel('Team member account').selectOption({ label: 'Zoe Nair' })
  await dialog.getByLabel('Onboarding').selectOption({ label: 'Standard onboarding' })
  await dialog.getByRole('button', { name: 'Hire' }).click()
  await expect(page.getByRole('heading', { name: 'Zoe Nair · E-2' })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Onboarding' })).toContainText('0 of 5 tasks done')
  expect(await emailText(hireEmail, "You've been assigned: Read the employee handbook")).toContain(
    'Read the employee handbook',
  )

  // The skill gap recommends a course; HR assigns it.
  await hrTab(page, 'Training').click()
  await page.getByRole('button', { name: 'New course' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Title').fill('Advanced negotiation')
  await dialog.getByRole('button', { name: 'Add skill' }).click()
  await dialog.getByLabel('Skill 1', { exact: true }).selectOption({ label: 'Negotiation' })
  await dialog.getByLabel('Skill 1 level').selectOption('4')
  await dialog.getByRole('button', { name: 'Create course' }).click()
  await expect(page.getByRole('region', { name: 'Courses' })).toContainText('Advanced negotiation')
  await hrTab(page, 'Employees').click()
  await page.getByRole('link', { name: 'Zoe Nair' }).click()
  const gap = page.getByRole('region', { name: 'Skills and gaps' })
  await expect(gap).toContainText('needs 4 · has none')
  await gap.getByRole('button', { name: 'Assign Advanced negotiation' }).click()
  await expect(page.getByText('Training assigned.')).toBeVisible()
  expect(await emailText(hireEmail, 'Training assigned: Advanced negotiation')).toContain(
    'Advanced negotiation',
  )

  // Zoe signs in, completes the training (the gap closes) and asks for two days off.
  await hire.goto('/login')
  await hire.getByLabel('Workspace URL').fill(ws.slug)
  await hire.getByLabel('Email').fill(hireEmail)
  await hire.getByLabel('Password', { exact: true }).fill(PASSWORD)
  await hire.getByRole('button', { name: 'Sign in' }).click()
  await expect(hire.getByRole('heading', { name: 'Welcome, Zoe' })).toBeVisible()
  await hire.goto('/app/hrms/me')
  await expect(hire.getByRole('heading', { name: 'My HR' })).toBeVisible()
  await hire.getByRole('button', { name: 'Mark Advanced negotiation complete' }).click()
  await hire.getByRole('dialog').getByRole('button', { name: 'Mark complete' }).click()
  await expect(hire.getByRole('region', { name: 'My training' })).toContainText(
    'No training assigned.',
  )
  const [start, end] = nextTuesdayAndWednesday()
  await hire.getByRole('button', { name: 'Request leave' }).click()
  dialog = hire.getByRole('dialog')
  await dialog.getByLabel('Leave type').selectOption({ label: 'Annual leave' })
  await dialog.getByLabel('From').fill(start)
  await dialog.getByLabel('To').fill(end)
  await dialog.getByRole('button', { name: 'Request leave' }).click()
  await expect(hire.getByText('Leave requested: 2 working days.')).toBeVisible()
  expect(await emailText(ws.email, `Leave request: Zoe Nair, ${start} to ${end}`)).toContain(
    '2 working day(s)',
  )

  // HR sees the closed gap and approves; Zoe hears about it.
  await page.reload()
  await expect(gap).toContainText('Meets every skill Account executive needs.')
  await hrTab(page, 'Leave').click()
  const queue = page.getByRole('region', { name: 'Waiting for a decision' })
  await queue.getByRole('button', { name: `Approve Zoe Nair ${start}` }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: 'Approve' }).click()
  await expect(queue.getByText('Nothing is waiting for a decision.')).toBeVisible()
  expect(await emailText(hireEmail, 'Your leave request was approved')).toContain(start)
  await expect(page.getByRole('region', { name: "Who's away" })).toContainText('Zoe Nair')

  // Zoe's balance and the overview reflect it.
  await hire.reload()
  await expect(hire.getByRole('region', { name: 'My leave' })).toContainText('18 days')
  await hrTab(page, 'Overview').click()
  const people = page.getByRole('region', { name: 'People' })
  // Ada and Zoe: the headcount figure itself, not any "2" in the region
  await expect(people).toContainText(/Active employees: 2(?!\d)/)
  await expect(page.getByRole('region', { name: 'Needs attention' })).toContainText(
    'Overdue trainings: 0',
  )
  await expect(page.getByRole('region', { name: 'Away in the next 7 days' })).toContainText(
    'Zoe Nair',
  )
  await hireContext.close()
})
