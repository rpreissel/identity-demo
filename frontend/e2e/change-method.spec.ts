import { expect, test } from '@playwright/test'
import { completeRegistration } from './journey'
import { ui, uiPattern, welcomeHeading } from './texts'

/**
 * Changing a method in place against the real backend: the method page offers "Ändern", the
 * enroll-sms form says it replaces the number, and the list comes back with sms still on it. The
 * registration just happened, so neither a step-up nor a fresh confirmation comes in between.
 */
test('change the phone number of the sms method', async ({ page }) => {
  await completeRegistration(page)

  await page.getByRole('button', { name: uiPattern('Sicherheit') }).first().click()
  await page.getByRole('button', { name: new RegExp(`^${ui('Anmeldeverfahren')}`) }).click()
  await page.getByRole('button', { name: /^SMS/ }).click()
  await page.getByRole('button', { name: ui('Ändern'), exact: true }).click()

  await expect(page.getByRole('heading', { name: ui('Telefonnummer ändern') })).toBeVisible({ timeout: 10_000 })
  await page.getByLabel(ui('Neue Telefonnummer')).fill('+49 170 0000099')
  await page.getByRole('button', { name: ui('Code senden'), exact: true }).click()
  // Demo mode pre-fills the TAN that was just sent.
  await page.getByRole('button', { name: ui('TAN bestätigen'), exact: true }).click()

  // The change ends the journey: back on the authenticated screens, sms still an active method.
  await expect(page.getByRole('heading', { name: welcomeHeading }).or(page.getByRole('button', { name: /^SMS/ })).first()).toBeVisible({ timeout: 10_000 })
  await expect(page.getByRole('heading', { name: ui('Telefonnummer ändern') })).toBeHidden()
  await expect(page.getByText(ui('Anmeldeverfahren geändert.'))).toBeVisible()
})
