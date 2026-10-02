import { expect, type Page } from '@playwright/test'
import { ui, uiPattern, welcomeHeading } from './texts'

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

  const success = page.getByRole('heading', { name: welcomeHeading })

  // SMS first so the resulting amr is predictable for assertions; the rest are the generic
  // "send a code / confirm a code" steps every enroll-* tool shares. Demo mode pre-fills the
  // phone number, e-mail and the just-issued TAN/code, so no typing is needed.
  // 'Einrichten' closes any enroll-* form whose fields demo mode already pre-filled (password
  // today). It comes last so the more specific labels win when both are on screen. After SMS
  // the choice of a method of another kind follows; the password is the one without a device
  // SDK. Anchored at the label, since the KOBIL hint mentions the password too.
  const passwordChoice = new RegExp(`^${ui('Passwort')} `)
  const steps = [uiPattern('SMS'), ui('Code senden'), ui('TAN bestätigen'), ui('Code bestätigen'), ui('Einrichten'), passwordChoice].map((name) =>
    // Exact for plain labels: a substring match found "einrichten" inside a menu row's hint
    // on the welcome screen and wandered off into confirming a browser login.
    page.getByRole('button', typeof name === 'string' ? { name, exact: true } : { name }).first(),
  )
  const anyStep = steps.reduce((either, step) => either.or(step))

  for (let round = 0; round < 12; round++) {
    // Wait for the next screen: the welcome, or a step to click.
    await expect(success.or(anyStep).first()).toBeVisible({ timeout: 10_000 })
    // Clicking on from the welcome would start something else entirely (the menu offers more
    // than the journey did).
    if (await success.isVisible()) break

    for (const button of steps) {
      if (await button.isVisible()) {
        await button.click()
        // The click re-renders the step; the next round must not catch this screen once more.
        await expect(button).toBeHidden({ timeout: 10_000 })
        break
      }
    }
  }

  await expect(success).toBeVisible({ timeout: 10_000 })
}
