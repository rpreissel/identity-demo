import { expect, type Page } from '@playwright/test'
import { ui, uiPattern } from './texts'

/**
 * Drives a fresh REGISTRATION journey to the authenticated screen.
 *
 * Loop-driven rather than a fixed click sequence: how many steps registration takes is a backend
 * policy decision. A hard-coded sequence breaks whenever that policy or the tool catalog changes.
 */
export async function completeRegistration(page: Page): Promise<void> {
  // Straight to the App channel, not via `/`: the root is the channel choice page, whose link opens
  // a named tab that Playwright would follow into a second page object.
  await page.goto('/app/')
  // A fresh browser context is a device no account knows: the phone offers a new account.
  await page.locator('.phone').getByRole('button', { name: ui('Neues Konto anlegen') }).click()

  // Two identification candidates (ident-eid, ident-fsc) mean a selection page rather than a skip
  // straight to the single one - pick Freischaltcode, whose form is fully pre-filled in demo mode.
  // Two screens (personal data, then the code) - both pre-filled.
  await page.getByRole('button', { name: uiPattern('Freischaltcode') }).click()
  await page.getByRole('button', { name: ui('Weiter zur Freischaltcode-Eingabe') }).click()
  await page.getByRole('button', { name: ui('Identifizieren') }).click()

  // The welcome that greets a logged-in user - its name part varies, so only the words before it.
  const success = page.getByRole('heading', { name: new RegExp(`^${ui('Willkommen, {name}!').split('{name}')[0]}`) })

  for (let step = 0; step < 12 && !(await success.isVisible()); step++) {
    // Every click re-renders the step and detaches the button mid-action - settle first rather
    // than racing the re-render.
    await page.waitForTimeout(600)
    // The last click may have landed on the welcome already - clicking on from there would start
    // something else entirely (the menu offers more than the journey did).
    if (await success.isVisible()) break

    // SMS first so the resulting amr is predictable for assertions; the rest are the generic
    // "send a code / confirm a code" steps every enroll-* tool shares. Demo mode pre-fills the
    // phone number, e-mail and the just-issued TAN/code, so no typing is needed.
    // 'Einrichten' closes any enroll-* form whose fields demo mode already pre-filled (password
    // today). It comes last so the more specific labels win when both are on screen. After SMS
    // the choice of a method of another kind follows; the password is the one without a device
    // SDK. Anchored at the label, since the KOBIL hint mentions the password too.
    const passwordChoice = new RegExp(`^${ui('Passwort')} `)
    for (const name of [uiPattern('SMS'), ui('Code senden'), ui('TAN bestätigen'), ui('Code bestätigen'), ui('Einrichten'), passwordChoice]) {
      // Exact for plain labels: a substring match found "einrichten" inside a menu row's hint
      // on the welcome screen and wandered off into confirming a browser login.
      const button = page.getByRole('button', typeof name === 'string' ? { name, exact: true } : { name }).first()
      if (await button.isVisible()) {
        await button.click()
        await page.waitForTimeout(800)
        break
      }
    }
  }

  await expect(success).toBeVisible({ timeout: 10_000 })
}
