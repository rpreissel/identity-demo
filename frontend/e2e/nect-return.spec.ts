import { expect, test } from '@playwright/test'
import { ui, uiPattern } from './texts'

/**
 * The redirect round trip to Nect, which only a browser can take (ADR-47): the app sends the user to
 * Nect's jump page, Nect sends them back to `/app/?nectCaseId=…`, and the app reports that case on
 * its own and carries on - here with the KVNR assignment that follows every identification. The
 * jump page is the simulated Nect (`/nect/`), driven through its own form.
 */
test('identifying at Nect returns to the app, which reports the case and goes on', async ({ page }) => {
  await page.goto('/app/')
  const phone = page.locator('.phone')
  await phone.getByRole('button', { name: ui('Neues Konto anlegen') }).click()
  await phone.getByRole('button', { name: uiPattern('Nect') }).first().click()
  await phone.getByRole('button', { name: ui('Weiter zu Nect') }).click()

  // Nect's own page: another app, with its own texts (the nect bundle), so its controls are found
  // by their ids. Jane Doe, so no other test's account is involved.
  await expect(page).toHaveURL(/\/nect\/\?case=/)
  await page.locator('#nect-person').selectOption({ label: 'Jane Doe' })
  await page.locator('#nect-pin').fill('123456')
  await page.locator('form button[type="submit"]').click()

  // Back in the app: the case number is read once and taken out of the address again, and the
  // journey went on to the next step without another click.
  await expect(page).toHaveURL(/\/app\/$/, { timeout: 10_000 })
  await expect(phone.getByRole('heading', { name: ui('Konto zuordnen') })).toBeVisible({ timeout: 10_000 })
  await expect(page.locator('body')).toContainText('nect-eid')
})
