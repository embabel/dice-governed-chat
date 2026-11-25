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

async function verifyReviewFlow(page: Page, capture?: (state: string) => Promise<unknown>) {
  await page.goto('/')
  const current = page.getByRole('region', { name: 'Current beliefs' })
  const pending = page.getByRole('region', { name: 'Pending review' })
  await expect(current.getByText(initial, { exact: true })).toBeVisible()
  await capture?.('support-desk')

  await page.getByRole('textbox', { name: 'Message support desk' }).fill(proposed)
  await page.getByRole('button', { name: 'Record as evidence' }).click()
  await expect(pending.getByText(proposed, { exact: true })).toBeVisible()
  await expect(current.getByText(initial, { exact: true })).toBeVisible()
  await expect(pending.getByRole('button', { name: 'Approve' })).toHaveCount(0)

  await pending.getByRole('button', { name: 'View source' }).click()
  const source = page.getByRole('dialog', { name: 'Source message' })
  await expect(source.getByText(proposed, { exact: true })).toBeVisible()
  await capture?.('source-evidence')
  await source.getByRole('button', { name: 'Close' }).click()

  await page.getByRole('button', { name: 'Reviewer', exact: true }).click()
  await capture?.('pending-review')
  await pending.getByRole('button', { name: 'Approve' }).press('Enter')
  await expect(current.getByText(proposed, { exact: true })).toBeVisible()
  await expect(pending.getByText('No pending changes.')).toBeVisible()
  const approvalStatus = pending.getByRole('status')
  await expect(approvalStatus).toHaveText('Change approved.')
  await expect(approvalStatus).toBeFocused()

  const history = page.getByRole('region', { name: 'Earlier beliefs' })
  await page.getByRole('button', { name: 'Before approval' }).click()
  await expect(history.getByText(initial, { exact: true })).toBeVisible()
  await expect(current.getByText(proposed, { exact: true })).toBeVisible()
  await capture?.('reviewed-history')

  await page.getByRole('button', { name: 'At baseline' }).click()
  await expect(history.getByText('No beliefs at this time.')).toBeVisible()

  await page.getByText('Choose a date and time', { exact: true }).click()
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

test('keeps the whole chat composer and suggested questions inside the mobile card', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await mockWorkspace(page)
  await page.goto('/')
  const card = await page.locator('.chat').boundingBox()
  expect(card).not.toBeNull()
  for (const control of [
    page.getByRole('textbox', { name: 'Message support desk' }),
    page.getByRole('button', { name: 'Record as evidence' }),
    page.getByRole('button', { name: /how long do customers have to request a refund/i }),
  ]) {
    const box = await control.boundingBox()
    expect(box).not.toBeNull()
    expect(box!.x + box!.width).toBeLessThan(card!.x + card!.width)
  }
  expect(await page.evaluate(() => document.documentElement.scrollWidth)).toBe(390)
})

test('asks a free-form question and redirects an unrelated one without recording evidence', async ({ page }) => {
  const state = { baseline, observedAt, claims: [initialClaim], reviews: [] }
  const reply = 'I can’t answer that from this support record. Would you like to know what refund policy is in effect?'
  await page.route(`${api}/state`, (route) => route.fulfill({ json: state }))
  await page.route(`${api}/chat`, (route) => {
    const { threadId, runId, messages, forwardedProps } = route.request().postDataJSON()
    expect(forwardedProps.intent).toBe('ask')
    const delta = messages.at(-1).content.includes('refund') ? initial : reply
    const messageId = runId + '-answer'
    const events = [
      { type: 'RUN_STARTED', threadId, runId },
      { type: 'TEXT_MESSAGE_START', messageId, role: 'assistant' },
      { type: 'TEXT_MESSAGE_CONTENT', messageId, delta },
      { type: 'TEXT_MESSAGE_END', messageId },
      { type: 'STATE_SNAPSHOT', snapshot: state },
      { type: 'RUN_FINISHED', threadId, runId },
    ]
    return route.fulfill({ contentType: 'text/event-stream',
      body: events.map((event) => `data: ${JSON.stringify(event)}\n\n`).join('') })
  })
  await page.goto('/')
  const message = page.getByRole('textbox', { name: 'Message support desk' })
  const chat = page.getByRole('log', { name: 'Conversation messages' })
  await message.fill('How long does a customer have to get a refund?')
  await page.getByRole('button', { name: 'Ask', exact: true }).click()
  await expect(chat.getByText(initial, { exact: true })).toBeVisible()
  await message.fill('Who discovered Neptune?')
  await page.getByRole('button', { name: 'Ask', exact: true }).click()
  await expect(chat.getByText(reply, { exact: true })).toBeVisible()
  await expect(page.getByRole('region', { name: 'Pending review' }).getByText('No pending changes.')).toBeVisible()
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

test('a delayed approval response keeps the newer beliefs from a chat refresh', async ({ page }) => {
  const policyReview = { id: 'refund-review', text: proposed, sourceId: 'refund-message', reason: 'Protected policy change' }
  const otherReview = { id: 'other-review', text: phone, sourceId: 'phone-message', reason: 'Conflicting follow-up preference' }
  const approvedClaim = { ...initialClaim, text: proposed, sources: ['refund-message'] }
  const olderApproval = {
    baseline, observedAt: '2026-11-21T12:00:01.123456789Z', claims: [approvedClaim], reviews: [otherReview],
  }
  let state = {
    baseline, observedAt: '2026-11-21T12:00:01.123456788Z', claims: [initialClaim], reviews: [policyReview, otherReview],
  }
  let releaseApproval!: () => void
  const delayApproval = new Promise<void>((resolve) => { releaseApproval = resolve })

  await page.route(`${api}/state`, (route) => route.fulfill({ json: state }))
  await page.route(`${api}/reviews/refund-review/approve`, async (route) => {
    await delayApproval
    await route.fulfill({ json: olderApproval })
  })
  await page.route(`${api}/chat`, (route) => {
    const { threadId, runId } = route.request().postDataJSON()
    state = {
      ...olderApproval,
      observedAt: '2026-11-21T12:00:01.12345679Z',
      claims: [approvedClaim, { id: 'new-belief', text: email, pinned: false, sources: ['new-message'] }],
    }
    return route.fulfill({
      contentType: 'text/event-stream',
      body: [
        { type: 'RUN_STARTED', threadId, runId },
        { type: 'RUN_FINISHED', threadId, runId },
      ].map((event) => `data: ${JSON.stringify(event)}\n\n`).join(''),
    })
  })

  await page.goto('/')
  await page.getByRole('button', { name: 'Reviewer', exact: true }).click()
  const pending = page.getByRole('region', { name: 'Pending review' })
  const first = pending.locator('article').filter({ hasText: proposed })
  const otherApproval = pending.locator('article').filter({ hasText: phone }).getByRole('button', { name: 'Approve' })
  const approving = page.waitForRequest(`${api}/reviews/refund-review/approve`)
  await first.getByRole('button', { name: 'Approve' }).click()
  await approving

  await page.getByRole('textbox', { name: 'Message support desk' }).fill(email)
  await page.getByRole('button', { name: 'Record as evidence' }).click()
  const current = page.getByRole('region', { name: 'Current beliefs' })
  await expect(current.getByText(email, { exact: true })).toBeVisible()
  await expect(otherApproval).toBeDisabled()

  releaseApproval()
  await expect(otherApproval).toBeEnabled()
  await expect(current.getByText(email, { exact: true })).toBeVisible({ timeout: 750 })
})

test('runs the review progression against the real application', async ({ page }, testInfo) => {
  test.skip(process.env.DICE_LIVE_E2E !== '1', 'Requires a fresh running DICE application')
  await verifyReviewFlow(page, async (state) => {
    const desktop = page.viewportSize()
    await page.evaluate(async () => { window.scrollTo(0, 0); await document.fonts.ready })
    await page.screenshot({
      path: testInfo.outputPath(`governed-${state}-desktop.png`),
      fullPage: state !== 'source-evidence',
      animations: 'disabled',
    })
    if (desktop && state === 'reviewed-history') {
      await page.setViewportSize({ width: 390, height: 844 })
      await page.screenshot({ path: testInfo.outputPath(`governed-${state}-mobile.png`), fullPage: true })
      await page.setViewportSize(desktop)
    }
  })

  await page.getByRole('button', { name: 'Support desk', exact: true }).click()
  const current = page.getByRole('region', { name: 'Current beliefs' })
  const pending = page.getByRole('region', { name: 'Pending review' })
  const message = page.getByRole('textbox', { name: 'Message support desk' })
  const send = page.getByRole('button', { name: 'Record as evidence' })
  await message.fill('How long does a customer have to request a refund?')
  await page.getByRole('button', { name: 'Ask', exact: true }).click()
  const chat = page.getByRole('log', { name: 'Conversation messages' })
  await expect(chat).toContainText(proposed)
  await chat.getByRole('button', { name: 'Evidence 1' }).click()
  const citedSource = page.getByRole('dialog', { name: 'Source message' })
  await expect(citedSource.getByText(proposed, { exact: true })).toBeVisible()
  await citedSource.getByRole('button', { name: 'Close' }).click()
  await expect(pending.getByText('No pending changes.')).toBeVisible()
  await page.evaluate(async () => {
    window.scrollTo(0, 0)
    await document.fonts.ready
  })
  await page.screenshot({ path: testInfo.outputPath('governed-ask-desktop.png'), fullPage: true, animations: 'disabled' })

  await page.reload()
  await page.getByRole('button', { name: 'Before approval' }).click()
  const comparison = page.getByLabel('Approval comparison')
  await expect(comparison.getByText(initial, { exact: true })).toBeVisible()
  await expect(comparison.getByText(proposed, { exact: true })).toBeVisible()

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
