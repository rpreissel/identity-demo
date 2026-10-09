import { expect, test, type Page } from '@playwright/test'
import { adminHeaders, ORCHESTRATOR } from './admin'
import {
  confirmEmailAndSetPassword,
  confirmSms,
  expectBackAtWebsite,
  identifyTestPerson,
  loginUrl,
  registeredPassword,
  registerTestPerson,
  resetDemo,
  sendEmailCode,
  sendSmsCode,
  visibleButton,
} from './website'
import { ui } from '../e2e/texts'
import { kc } from './texts'

/**
 * The login pages against the real compose stack (docs/adr/ADR-057-keycloakify-einziges-login-theme.md):
 * the theme carries a whole sign-in through. The note in the dark band says the theme, not
 * Keycloak's own pages, drew a page. The demo has no accounts of its own, so the
 * suite resets it first and registers the account it signs in with through the website itself.
 */

async function expectDrawnByTheme(page: Page) {
  await expect(page.getByText(kc('Erstellt mit {technik}', { technik: 'Keycloakify' }))).toBeVisible()
  await expect(page.getByText(kc('Sie sind jetzt bei Keycloak, dem Anmeldedienst dieser Website.'))).toBeVisible()
}

/**
 * Opens the SMS page and leaves it again - nothing is sent before "Weiter", so this stays clear
 * of the orchestrator's send throttle (three per account and window), which a suite run twice in
 * a row would otherwise hit.
 */
async function visitSmsAndGoBack(page: Page) {
  await page.getByRole('button', { name: 'SMS', exact: true }).click()
  await expectDrawnByTheme(page)
  await expect(page.getByLabel(kc('E-Mail-Adresse'))).not.toHaveValue('')
  await page.getByRole('button', { name: kc('Zurück') }).click()
  await expect(page.getByRole('button', { name: kc('Abbrechen') })).toBeVisible()
}

/**
 * The whole sign-in by e-mail address and password: the demo person is pre-filled, the password is
 * the one the suite registered.
 */
async function signInByPassword(page: Page) {
  await page.getByRole('button', { name: ui('Passwort'), exact: true }).click()
  await expectDrawnByTheme(page)
  await expect(page.getByLabel(kc('E-Mail-Adresse'))).not.toHaveValue('')
  await page.getByLabel(kc('Passwort'), { exact: true }).fill(registeredPassword)
  await page.getByRole('button', { name: kc('Weiter') }).click()
  await expectBackAtWebsite(page)
}

test.beforeAll(async ({ browser, request }) => {
  await resetDemo(request)

  const context = await browser.newContext()
  await registerTestPerson(await context.newPage())
  await context.close()
})

test.afterAll(async ({ request }) => {
  // Back to the start, without the account this suite registered: the next visitor registers for real.
  const reset = await request.post(`${ORCHESTRATOR}/orchestrator/admin/demo-reset`, { headers: adminHeaders })
  expect(reset.ok()).toBeTruthy()
  // The deleted account's sessions are gone in Keycloak too, also the ones it keeps in its database.
  const sessions = await (await request.get(`${ORCHESTRATOR}/orchestrator/admin/sessions`, { headers: adminHeaders })).json()
  expect(sessions.keycloak.error ?? null).toBeNull()
  expect(sessions.keycloak.clients.map((client: { count: number }) => client.count)).toEqual(
    sessions.keycloak.clients.map(() => 0),
  )
})

test('the method selection, the SMS page and back, then a whole sign-in', async ({ page }) => {
  await page.goto(loginUrl())
  await expectDrawnByTheme(page)
  await expect(page.getByRole('button', { name: kc('Abbrechen') })).toBeVisible()
  await visitSmsAndGoBack(page)
  await signInByPassword(page)
})

test('"Zurück" on the Freischaltcode page shows the personal details again', async ({ page }) => {
  await page.goto(loginUrl())
  await page.getByRole('link', { name: kc('Registrieren') }).click()
  await page.getByRole('button', { name: ui('Freischaltcode'), exact: true }).click()

  const personal = page.getByText(kc('Damit Sie Ihren Freischaltcode gleich eingeben können, brauchen wir noch diese Daten:'))
  const code = page.getByText(kc('Geben Sie den Freischaltcode ein, den wir Ihnen per Brief geschickt haben.'))

  await expect(personal).toBeVisible()
  await visibleButton(page, kc('Weiter zur Freischaltcode-Eingabe')).click()
  await expect(code).toBeVisible()

  // Within the tool: back to the personal details, and on from there to the code again.
  await visibleButton(page, kc('Zurück')).click()
  await expect(personal).toBeVisible()
  await visibleButton(page, kc('Weiter zur Freischaltcode-Eingabe')).click()
  await expect(code).toBeVisible()
})

test('"Zurück" in a code step of the registration shows the address or number again', async ({ page, request }) => {
  // A registration of its own: the account from beforeAll would make this one a login.
  await resetDemo(request)
  await identifyTestPerson(page)
  await sendEmailCode(page)

  await test.step('e-mail code: back to the address mask, within the page, and on again', async () => {
    await visibleButton(page, kc('Zurück')).click()
    await expect(page.getByLabel(kc('E-Mail-Adresse')).filter({ visible: true })).toBeVisible()
    await expect(page.getByLabel(kc('Bestätigungscode'))).toBeHidden()
    await visibleButton(page, kc('Zurück')).click()
    await expect(page.getByLabel(kc('Bestätigungscode'))).toBeVisible()
  })
  await confirmEmailAndSetPassword(page)
  await sendSmsCode(page)

  await test.step('SMS code: back to the number, within the page, and on again', async () => {
    await visibleButton(page, kc('Zurück')).click()
    await expect(page.getByLabel(kc('Telefonnummer'), { exact: true }).filter({ visible: true })).toBeVisible()
    await visibleButton(page, kc('Zurück')).click()
    await expect(page.getByLabel(kc('SMS-Code'))).toBeVisible()
  })
  // Completed, so the tests after this one find the account again.
  await confirmSms(page)
})

test('the QR waiting page asks in the background and does not reload', async ({ page }) => {
  await page.goto(loginUrl())
  await page.getByRole('button', { name: ui('Mit App anmelden'), exact: true }).click()
  await expectDrawnByTheme(page)

  const pairingCode = page.locator('.orc-qr-code')
  const shownCode = await pairingCode.textContent()
  expect(shownCode).toBeTruthy()

  // From here on nothing may load the page again while the app has not decided (ADR-45).
  let reloads = 0
  page.on('load', () => reloads++)
  const answers: { status: number; state?: string }[] = []
  page.on('response', async (response) => {
    if (!response.url().includes('/orchestrator-qr/status')) return
    answers.push({ status: response.status(), state: (await response.json().catch(() => ({}))).state })
  })

  await expect.poll(() => answers.length, { timeout: 15_000 }).toBeGreaterThanOrEqual(1)
  expect(answers.every((answer) => answer.status === 200 && answer.state === 'waiting')).toBe(true)
  expect(reloads).toBe(0)
  await expect(pairingCode).toHaveText(shownCode ?? '')

  // "Abbrechen" still leaves the page, back to the selection - without the method just declined.
  await page.getByRole('button', { name: kc('Abbrechen') }).click()
  await expect(page.getByRole('button', { name: ui('Passwort'), exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: ui('Mit App anmelden'), exact: true })).toHaveCount(0)
})

// Keycloak asks for nothing itself (ADR-58): its first page is always the orchestrator's selection.
test('the first page is the method selection, never a password form of its own', async ({ page }) => {
  await page.goto(loginUrl())

  await expect(page.getByRole('button', { name: 'SMS', exact: true })).toBeVisible()
  await expect(page.locator('input[type="password"]')).toHaveCount(0)
})
