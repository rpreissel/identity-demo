import { expect, test } from '@playwright/test'
import { completeRegistration } from './journey'
import { ui, uiPattern } from './texts'

/**
 * Registration -> enrollment -> authenticated against the real backend: real DPoP WebCrypto proofs
 * are accepted, and the security summary's on-demand fetch (docs/05-api.md #2) lands real data
 * from `GET /channels/{id}`.
 *
 * Uses the first test person's pre-filled data (A123456789, demo_seed/V16__testdata.sql). That needs a DB without an
 * account for it, otherwise the journey is a LOGIN; playwright.config.ts always starts its own
 * server on a fresh in-memory DB.
 */
test('register with ident-fsc, enroll SMS, and reach the authenticated security summary', async ({ page }) => {
  await completeRegistration(page)

  // The account fields arrive only via the on-demand GET, never inline in the tool response. If
  // this renders, the real fetch against the real backend succeeded. They live on the security screen.
  await page.getByRole('button', { name: uiPattern('Sicherheit') }).first().click()
  await expect(page.locator('li').filter({ hasText: ui('Sicherheitsniveau') })).toContainText(/loa[12]/)
  await expect(page.locator('li').filter({ hasText: ui('Genutzte Anmeldeverfahren') })).toContainText('sms')
})
