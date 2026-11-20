import { expect, test, type Page } from '@playwright/test'
import { newWorkspace, signIn, signUpAndVerify } from './support/workspace'

function helpdeskTab(page: Page, name: string) {
  return page.getByRole('navigation', { name: 'HelpDesk' }).getByRole('link', { name })
}

function hrTab(page: Page, name: string) {
  return page.getByRole('navigation', { name: 'HRMS' }).getByRole('link', { name })
}

const REFUND_POLICY =
  'Refunds are processed within 5 working days of approval. A refund goes back to the original payment method.'
const REMOTE_POLICY =
  'Employees may work remotely up to 2 days a week with manager approval. Remote days must be agreed in advance.'

test('AI suggests triage, drafts a reply from the knowledge base and answers a policy question, all from the workspace', async ({
  page,
}) => {
  const ws = newWorkspace('ai')
  const customerEmail = `dana-${ws.slug}@e2e.test`
  await signUpAndVerify(page, ws)
  await signIn(page, ws, ws.email)
  await expect(page.getByRole('heading', { name: 'Welcome, Ada' })).toBeVisible()
  const nav = page.getByRole('navigation', { name: 'Workspace' })
  const settingsNav = page.getByRole('navigation', { name: 'Settings' })

  // HelpDesk and HRMS (a free workspace may have two modules).
  await page.goto('/app/settings/modules')
  await page.getByRole('switch', { name: 'HelpDesk' }).click()
  await expect(page.getByText('HelpDesk enabled.')).toBeVisible()
  await page.getByRole('switch', { name: 'HRMS' }).click()
  await expect(page.getByText('HRMS enabled.')).toBeVisible()

  // Every workspace starts with a Billing category (among others) for triage to pick.
  await settingsNav.getByRole('link', { name: 'HelpDesk' }).click()
  await expect(
    page.getByRole('region', { name: 'Ticket categories' }).getByRole('row', { name: /Billing/ }),
  ).toBeVisible()

  // A published knowledge-base article about refunds.
  await nav.getByRole('link', { name: 'HelpDesk' }).click()
  await helpdeskTab(page, 'Knowledge base').click()
  await page.getByRole('button', { name: 'New article' }).click()
  let dialog = page.getByRole('dialog')
  await dialog.getByLabel('Title').fill('Refund requests')
  await dialog.getByLabel('Article').fill(REFUND_POLICY)
  await dialog.getByRole('button', { name: 'Save draft' }).click()
  await expect(page.getByRole('heading', { name: 'Refund requests' })).toBeVisible()
  await page.getByRole('button', { name: 'Publish' }).click()
  await expect(page.getByText('Article published.')).toBeVisible()

  // Ada is an employee (My HR needs a linked employee record) and a published policy about remote work.
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
  await hrTab(page, 'Policies').click()
  await page.getByRole('button', { name: 'New policy' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('Title').fill('Remote work')
  await dialog.getByLabel('Policy').fill(REMOTE_POLICY)
  await dialog.getByRole('button', { name: 'Save draft' }).click()
  await expect(page.getByRole('heading', { name: 'Remote work' })).toBeVisible()
  await page.getByRole('button', { name: 'Publish' }).click()
  await expect(page.getByText('Policy published.')).toBeVisible()

  // Nothing has been asked yet. Both documents are indexed after their publish commits, in the background:
  // wait for the index so the answers below can't race it.
  await page.goto('/app/settings/ai')
  const usage = page.getByRole('region', { name: 'Usage' })
  await expect(usage).toContainText('0 of 500')
  await expect(async () => {
    await page.reload()
    await expect(page.getByRole('region', { name: 'Search index' })).toContainText(
      /Indexed passages\s*2/,
      { timeout: 2_000 },
    )
  }).toPass({ timeout: 30_000 })

  // The customer, and a ticket from her page.
  await nav.getByRole('link', { name: 'Directory' }).click()
  await page.getByRole('button', { name: 'New person' }).click()
  dialog = page.getByRole('dialog')
  await dialog.getByLabel('First name').fill('Dana')
  await dialog.getByLabel('Last name').fill('Shah')
  await dialog.getByLabel('Email').fill(customerEmail)
  await dialog.getByRole('button', { name: 'Create person' }).click()
  await expect(page.getByRole('heading', { name: 'Dana Shah' })).toBeVisible()
  await page
    .getByRole('region', { name: 'Tickets' })
    .getByRole('button', { name: 'New ticket' })
    .click()
  dialog = page.getByRole('dialog')
  await expect(dialog.getByLabel('Requester', { exact: true })).toHaveValue(/.+/)
  await dialog.getByLabel('Subject').fill('Customer charged twice — wants a refund')
  await dialog
    .getByLabel('Description')
    .fill('She was billed twice for the same invoice and would like the duplicate charge refunded.')
  await dialog.getByRole('button', { name: 'Create ticket' }).click()
  await expect(
    page.getByRole('heading', { name: 'T-00001 · Customer charged twice — wants a refund' }),
  ).toBeVisible()
  const details = page.getByRole('region', { name: 'Details' })
  await expect(details).toContainText('Category—')

  // Triage: the AI proposes Billing; the agent applies it through the ordinary ticket update.
  const assist = page.getByRole('region', { name: 'AI assist' })
  await assist.getByRole('button', { name: 'Suggest triage' }).click()
  await expect(assist.getByText('AI-generated — check before use')).toBeVisible()
  await expect(assist.getByText('Billing', { exact: true })).toBeVisible()
  await assist.getByRole('button', { name: 'Apply' }).click()
  await expect(page.getByText('Triage applied.')).toBeVisible()
  await expect(details).toContainText('CategoryBilling')

  // The reply draft lands in the composer, unsent, from the refund article.
  await assist.getByRole('button', { name: 'Draft reply' }).click()
  await expect(page.getByLabel('Message', { exact: true })).toHaveValue(/refund/i)
  await expect(assist.getByRole('list', { name: 'Sources' })).toContainText('Refund requests')

  // My HR: a question answered from the policy, quoting it and linking to it.
  await nav.getByRole('link', { name: 'HRMS' }).click()
  await hrTab(page, 'My HR').click()
  const ask = page.getByRole('region', { name: 'Ask about HR policies' })
  await ask.getByLabel('Your question').fill('How many days can I work remotely?')
  await ask.getByRole('button', { name: 'Ask' }).click()
  await expect(ask.getByText("Answered from your workspace's documents")).toBeVisible()
  await expect(ask).toContainText('2 days')
  await expect(
    ask.getByRole('list', { name: 'Sources' }).getByRole('link', { name: 'Remote work' }),
  ).toBeVisible()

  // Triage, the draft and the question: three requests counted for the month.
  await page.goto('/app/settings/ai')
  await expect(page.getByRole('region', { name: 'Usage' })).toContainText('3 of 500')
})
