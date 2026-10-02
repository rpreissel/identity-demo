import { expect, test } from '@playwright/test'
import { ui, uiPattern } from './texts'

/**
 * "Zurück" leaves a tool without declining it (docs/05-api.md, "Zurück und Verfahren wechseln"):
 * the selection it came from comes back, the tool still on it. Declining instead would push a user
 * with one identification left straight into the next one, without a choice.
 */
test('going back from the Freischaltcode shows the identification choice again', async ({ page }) => {
  await page.goto('/app/')
  // A fresh browser context is a device no account knows: the phone offers a new account.
  await page.locator('.phone').getByRole('button', { name: ui('Neues Konto anlegen') }).click()

  const fsc = page.getByRole('button', { name: uiPattern('Freischaltcode') })
  await fsc.click()
  await expect(page.getByRole('button', { name: ui('Weiter zur Freischaltcode-Eingabe') })).toBeVisible()

  await page.getByRole('button', { name: ui('Zurück'), exact: true }).click()

  // The choice again - both ways, the Freischaltcode included.
  await expect(fsc).toBeVisible()
  await expect(page.getByRole('button', { name: uiPattern('eID') })).toBeVisible()
})
