import { expect, test, type Page } from '@playwright/test'
import { ui, uiPattern, welcomeHeading } from './texts'

/** Erika Beispiel (demo_seed): no other test of this suite registers her. */
const ERIKA = 'P000000002'

/**
 * Registers Erika with the Freischaltcode and her confirmed e-mail address, up to the choice of the
 * first sign-in method. The demo picker fills each form with her data; demo mode pre-fills the codes.
 */
async function registerUpToMethodChoice(page: Page): Promise<void> {
  await page.goto('/app/')
  const phone = page.locator('.phone')
  await phone.getByRole('button', { name: ui('Neues Konto anlegen') }).click()
  await phone.getByRole('button', { name: uiPattern('Freischaltcode') }).click()
  await page.locator('#demoPerson').selectOption(ERIKA)
  await page.getByRole('button', { name: ui('Weiter zur Freischaltcode-Eingabe') }).click()
  await page.getByRole('button', { name: ui('Identifizieren') }).click()
  await page.locator('#demoPerson').selectOption(ERIKA)
  await page.getByRole('button', { name: ui('Code senden'), exact: true }).click()
  await page.getByRole('button', { name: ui('Code bestätigen'), exact: true }).click()
}

/**
 * KOBIL in a browser: the activation leaves an unlock secret in this browser's storage
 * (tools/kobil/localData.ts), and a later sign-in on the same device unlocks with it - something only
 * a real browser with a persistent DPoP key and storage can show. The backend side is covered by
 * KobilBindingIntegrationTest.
 */
test('KOBIL set up with biometrics signs in again on the same device', async ({ page }) => {
  await registerUpToMethodChoice(page)
  const phone = page.locator('.phone')

  await phone.getByRole('button', { name: uiPattern('KOBIL') }).first().click()
  await phone.getByLabel(ui('Gerätename')).fill('Testhandy')
  await phone.getByRole('button', { name: ui('Weiter'), exact: true }).click()
  await phone.getByRole('button', { name: ui('Biometrie erlauben'), exact: true }).click()

  // The account may ask for one more method of another kind; SMS is the one without an SDK.
  const welcome = phone.getByRole('heading', { name: welcomeHeading })
  const sms = phone.getByRole('button', { name: uiPattern('SMS') }).first()
  await expect(welcome.or(sms)).toBeVisible({ timeout: 10_000 })
  if (await sms.isVisible()) {
    await sms.click()
    await phone.getByRole('button', { name: ui('Code senden'), exact: true }).click()
    await phone.getByRole('button', { name: ui('TAN bestätigen'), exact: true }).click()
  }
  await expect(welcome).toBeVisible({ timeout: 10_000 })

  // Sign out (welcome, then the backend's "really?"), and in again on this device.
  await phone.getByRole('button', { name: ui('Abmelden'), exact: true }).click()
  await expect(welcome).toBeHidden()
  await phone.getByRole('button', { name: ui('Abmelden'), exact: true }).click()
  await phone.getByRole('button', { name: ui('Mit diesem Gerät anmelden') }).click()

  // The device's own credential is offered at once, and the stored secret unlocks it.
  await phone.getByRole('button', { name: ui('Mit Biometrie entsperren') }).click()
  await expect(welcome).toBeVisible({ timeout: 10_000 })
  await expect(page.locator('body')).toContainText('kobil')
  await expect(page.locator('body')).toContainText('biometric')
})
