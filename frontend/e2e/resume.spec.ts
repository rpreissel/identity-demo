import { expect, test } from '@playwright/test'
import { ui, uiPattern } from './texts'

/** Paula Schulz (demo_seed), a partner without KVNR: no other test of this suite registers her. */
const PAULA = 'P000000004'

interface SentSms {
  sequence: number
  phoneNumber: string
  tan: string
}

/**
 * A reload in the middle of a tool: the app keeps its channel in this browser and offers to go on
 * where the user left off. Resuming re-reads the running step from the tool's GET
 * (`GET …/tools/{toolSessionId}/{toolId}`) instead of activating the tool again - so the TAN screen
 * comes back and no second SMS is sent.
 */
test('a reload during an SMS enrollment resumes the TAN step without a second SMS', async ({ page }) => {
  await page.goto('/app/')
  const phone = page.locator('.phone')
  await phone.getByRole('button', { name: ui('Neues Konto anlegen') }).click()
  await phone.getByRole('button', { name: uiPattern('Freischaltcode') }).click()
  await page.locator('#demoPerson').selectOption(PAULA)
  await page.getByRole('button', { name: ui('Weiter zur Freischaltcode-Eingabe') }).click()
  await page.getByRole('button', { name: ui('Identifizieren') }).click()
  await page.locator('#demoPerson').selectOption(PAULA)
  await page.getByRole('button', { name: ui('Code senden'), exact: true }).click()
  await page.getByRole('button', { name: ui('Code bestätigen'), exact: true }).click()

  await phone.getByRole('button', { name: uiPattern('SMS') }).first().click()
  // Demo mode pre-fills the same number for every person, and earlier tests of this run texted it
  // too: only the SMS sent after this point count.
  const number = (await phone.getByRole('textbox').first().inputValue()).replace(/\s/g, '')
  const outbox = async () => (await (await page.request.get('/mock-sms/outbox')).json()) as SentSms[]
  const before = Math.max(0, ...(await outbox()).map((sms) => sms.sequence))
  const smsTo = async () => (await outbox()).filter((sms) => sms.phoneNumber === number && sms.sequence > before)

  await phone.getByRole('button', { name: ui('Code senden'), exact: true }).click()
  const tanStep = phone.getByRole('heading', { name: ui('TAN eingeben') })
  await expect(tanStep).toBeVisible()
  await expect.poll(async () => (await smsTo()).length).toBe(1)

  await page.reload()
  await page.getByRole('button', { name: new RegExp(`^${ui('Sitzung fortsetzen')}`) }).click()

  // The same step again, read back rather than started anew: still exactly one SMS.
  await expect(tanStep).toBeVisible({ timeout: 10_000 })
  expect(await smsTo()).toHaveLength(1)

  // And the TAN of that one SMS still completes the step.
  const [sms] = await smsTo()
  await phone.getByRole('textbox').first().fill(sms.tan)
  await phone.getByRole('button', { name: ui('TAN bestätigen'), exact: true }).click()
  await expect(tanStep).toBeHidden({ timeout: 10_000 })
  await expect(page.locator('body')).toContainText('sms')
})
