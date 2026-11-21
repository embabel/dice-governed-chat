import { expect, test, type Page } from '@playwright/test'

const api = '**/api/workspaces/acme'
const initial = 'Support leads can approve refunds within 30 days.'
const proposed = 'Support leads can approve refunds within 60 days.'
const email = 'Email is our preferred follow-up channel.'
const phone = 'Phone is our preferred follow-up channel.'
const baseline = '2026-11-21T12:00:00Z'
const observedAt = '2026-11-21T12:00:01Z'
const initialClaim = { id: 'initial-refunds', text: initial, pinned: true, sources: ['policy-guide'] }

async function mockWorkspace(page: Page) {
  let review = false
  let approved = false
  let sourceId: string
  const workspace = () => ({
    baseline,
    observedAt,
    claims: approved ? [{ ...initialClaim, text: proposed, sources: [sourceId] }] : [initialClaim],
    reviews: review && !approved
      ? [{ id: 'refund-review', text: proposed, sourceId, reason: 'Conflicts with protected policy' }]
      : [],
  })

  await page.route(`${api}/state`, (route) => {
    expect(route.request().headers().authorization).toMatch(/^Bearer (desk|reviewer)-demo$/)
    return route.fulfill({ json: workspace() })
  })
  await page.route(`${api}/chat`, (route) => {
    expect(route.request().headers().authorization).toBe('Bearer desk-demo')
    const { threadId, runId, messages } = route.request().postDataJSON()
    expect(messages.at(-1)).toMatchObject({ role: 'user', content: proposed })
    sourceId = messages.at(-1).id
    review = true
    const messageId = 'assistant-1'
    const events = [
      { type: 'RUN_STARTED', threadId, runId },
      { type: 'TEXT_MESSAGE_START', messageId, role: 'assistant' },
      { type: 'TEXT_MESSAGE_CONTENT', messageId, delta: 'I sent the change for review.' },
      { type: 'TEXT_MESSAGE_END', messageId },
      { type: 'STATE_SNAPSHOT', snapshot: workspace() },
      { type: 'RUN_FINISHED', threadId, runId },
    ]
    return route.fulfill({
      contentType: 'text/event-stream',
      body: events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join(''),
    })
  })
  await page.route(`${api}/reviews/refund-review/approve`, (route) => {
    expect(route.request().headers().authorization).toBe('Bearer reviewer-demo')
    expect(route.request().method()).toBe('POST')
    approved = true
    return route.fulfill({ json: workspace() })
  })
  await page.route(`${api}/sources/*`, (route) => {
    expect(route.request().url()).toContain(sourceId)
    return route.fulfill({ json: { id: sourceId, text: proposed } })
  })
  await page.route(`${api}/history?*`, (route) => {
    const at = new URL(route.request().url()).searchParams.get('asOf')
    if (at && new Date(at) < new Date(baseline)) return route.fulfill({ status: 422 })
    return route.fulfill({ json: { asOf: at, claims: at === baseline ? [] : [{ ...initialClaim, sources: [] }] } })
  })
}

async function verifyReviewFlow(page: Page) {
  await page.goto('/')
  const current = page.getByRole('region', { name: 'Current beliefs' })
  const pending = page.getByRole('region', { name: 'Pending review' })
  await expect(current.getByText(initial, { exact: true })).toBeVisible()

  await page.getByRole('textbox', { name: 'Message support desk' }).fill(proposed)
  await page.getByRole('button', { name: 'Send message' }).click()
  await expect(pending.getByText(proposed, { exact: true })).toBeVisible()
  await expect(current.getByText(initial, { exact: true })).toBeVisible()
  await expect(pending.getByRole('button', { name: 'Approve' })).toHaveCount(0)

  await pending.getByRole('button', { name: 'View source' }).click()
  const source = page.getByRole('dialog', { name: 'Source message' })
  await expect(source.getByText(proposed, { exact: true })).toBeVisible()
  await source.getByRole('button', { name: 'Close' }).click()

  await page.getByRole('button', { name: 'Reviewer', exact: true }).click()
  await pending.getByRole('button', { name: 'Approve' }).click()
  await expect(current.getByText(proposed, { exact: true })).toBeVisible()
  await expect(pending.getByText('No pending changes.')).toBeVisible()

  const history = page.getByRole('region', { name: 'Earlier beliefs' })
  await page.getByRole('button', { name: 'Before approval' }).click()
  await expect(history.getByText(initial, { exact: true })).toBeVisible()
  await expect(current.getByText(proposed, { exact: true })).toBeVisible()

  await page.getByRole('button', { name: 'At baseline' }).click()
  await expect(history.getByText('No beliefs at this time.')).toBeVisible()

  await page.getByLabel('As of date and time').fill('2020-01-01T12:00')
  await page.getByRole('button', { name: 'Read earlier beliefs' }).click()
  await expect(page.getByRole('alert')).toHaveText('No complete baseline covers that time.')
}

test('shows the currently approved refund policy to the support desk', async ({ page }) => {
  await mockWorkspace(page)
  await page.goto('/')
  await expect(page.getByRole('heading', { name: 'Support desk' })).toBeVisible()
  await expect(page.getByText(initial, { exact: true })).toBeVisible()
})

test('reviews a proposed policy change and keeps earlier beliefs inspectable', async ({ page }) => {
  await mockWorkspace(page)
  await verifyReviewFlow(page)
})

test('a reviewer can inspect an unresolved decision without approving it', async ({ page }) => {
  const reason = 'The revision decision did not provide a usable answer.'
  await page.route(`${api}/state`, (route) => route.fulfill({
    json: {
      baseline, observedAt, claims: [initialClaim],
      reviews: [{ id: 'unresolved', text: phone, sourceId: 'phone-message', reason, approvable: false }],
    },
  }))

  await page.goto('/')
  await page.getByRole('button', { name: 'Reviewer', exact: true }).click()
  const pending = page.getByRole('region', { name: 'Pending review' })
  await expect(pending.getByText(phone, { exact: true })).toBeVisible()
  await expect(pending.getByText(reason, { exact: true })).toBeVisible()
  await expect(pending.getByRole('button', { name: 'View source' })).toBeVisible()
  await expect(pending.getByRole('button', { name: 'Approve' })).toHaveCount(0, { timeout: 750 })
})

test('runs the review progression against the real application', async ({ page }) => {
  test.skip(process.env.DICE_LIVE_E2E !== '1', 'Requires a fresh running DICE application')
  await verifyReviewFlow(page)

  await page.getByRole('button', { name: 'Support desk', exact: true }).click()
  const current = page.getByRole('region', { name: 'Current beliefs' })
  const pending = page.getByRole('region', { name: 'Pending review' })
  const message = page.getByRole('textbox', { name: 'Message support desk' })
  const send = page.getByRole('button', { name: 'Send message' })
  await message.fill(email)
  await send.click()
  await expect(current.getByText(email, { exact: true })).toBeVisible()
  await message.fill(phone)
  await send.click()
  await expect(pending.getByText(phone, { exact: true })).toBeVisible()
  await expect(pending.getByText(/the revision decision found an incompatible value/i)).toBeVisible()
  await expect(current.getByText(email, { exact: true })).toBeVisible()
  await expect(current.getByText(phone, { exact: true })).toHaveCount(0)
})
