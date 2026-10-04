import { expect, test } from '@playwright/test'
import { completeRegistration } from './journey'
import { SELF_SERVICE_MAX_AGE_SECONDS } from '../playwright.config'
import { ui, uiPattern } from './texts'

/**
 * Managing methods asks for a fresh proof once the session's latest one is older than
 * `identity.policy.self-service-max-age` (docs/04-orchestrierung.md #8). The suite's backend runs
 * with a short limit; this spec waits it out, then finds the confirmation between the wish and
 * the change - and the change itself right after it, without asking again.
 */
test('changing a method on an older session asks for a fresh proof first', async ({ page }) => {
  test.setTimeout(60_000)
  await completeRegistration(page)
  await page.waitForTimeout((SELF_SERVICE_MAX_AGE_SECONDS + 1) * 1000)

  await page.getByRole('button', { name: uiPattern('Sicherheit') }).first().click()
  await page.getByRole('button', { name: new RegExp(`^${ui('Anmeldeverfahren')}`) }).click()
  await page.getByRole('button', { name: /^SMS/ }).click()
  await page.getByRole('button', { name: ui('Ändern'), exact: true }).click()

  // Any active method will do; the password is the one that needs no code.
  await expect(page.getByText(ui('Ihr letzter Nachweis liegt länger zurück. Bitte bestätigen Sie zuerst eines Ihrer Anmeldeverfahren.'))).toBeVisible({
    timeout: 10_000,
  })
  await page.getByRole('button', { name: new RegExp(`^${ui('Passwort')}`) }).first().click()
  await page.getByRole('button', { name: ui('Anmelden'), exact: true }).click()

  // The proof carries out the wish: the number's enrollment, saying that it replaces.
  await expect(page.getByRole('heading', { name: ui('Telefonnummer ändern') })).toBeVisible({ timeout: 10_000 })
})
