import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { adminHeaders, MADE_WITH, ORCHESTRATOR, switchLoa1Login, switchTheme, THEMES, type Theme } from './admin'
import { ui } from '../e2e/texts'
import { kc } from './texts'

/**
 * Both login themes against the real compose stack (docs/adr/ADR-041-keycloakify-neben-freemarker.md):
 * the orchestrator's switch sets the realm's theme, and either theme carries a whole sign-in
 * through - same pages, same field names, same outcome. The note in the dark band says which
 * theme drew a page. The pages under test need loa1 on the orchestrator's method selection
 * (ADR-42), so the suite switches it there explicitly. The demo has no accounts of its own, so the
 * suite resets it first and registers the account it signs in with through the website itself.
 */

const KEYCLOAK = process.env.KEYCLOAK_URL ?? 'https://localhost:8543'
/** The password the suite's own registration set: the demo password the page offered. */
let registeredPassword = ''

/** The demo's start: no accounts, FreeMarker, the method selection. */
async function resetDemo(request: APIRequestContext) {
  const reset = await request.post(`${ORCHESTRATOR}/orchestrator/admin/demo-reset`, { headers: adminHeaders })
  expect(reset.status()).toBe(200)
  await switchLoa1Login(request, 'ORCHESTRATOR')
}

/** The demo value a page shows next to its field, e.g. "Demo-Code: 123456" (ADR-28). */
async function demoValue(page: Page, template: string): Promise<string> {
  const [before, after] = kc(template).split('{wert}')
  const escape = (text: string) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const text = (await page.getByText(new RegExp(`${escape(before)}\\S+${escape(after)}`)).textContent()) ?? ''
  const start = text.indexOf(before) + before.length
  return text.slice(start, after ? text.indexOf(after, start) : undefined).trim()
}

/**
 * The web login as the demo website starts it, with loa1 switched to the orchestrator's method
 * selection (beforeAll). PKCE only has to be well-formed: the suite stops at the code.
 */
function loginUrl(): string {
  const url = new URL(`${KEYCLOAK}/realms/Demo/protocol/openid-connect/auth`)
  url.searchParams.set('client_id', 'identity-demo-web')
  url.searchParams.set('redirect_uri', `${ORCHESTRATOR}/`)
  url.searchParams.set('response_type', 'code')
  url.searchParams.set('scope', 'openid')
  url.searchParams.set('acr_values', '1')
  url.searchParams.set('code_challenge_method', 'S256')
  url.searchParams.set('code_challenge', 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM')
  return url.toString()
}

async function expectDrawnBy(page: Page, theme: Theme) {
  await expect(page.getByText(kc('Erstellt mit {technik}', { technik: MADE_WITH[theme] }))).toBeVisible()
  await expect(page.getByText(kc('Sie sind jetzt bei Keycloak, dem Anmeldedienst dieser Website.'))).toBeVisible()
}

/**
 * Opens the SMS page and leaves it again - nothing is sent before "Weiter", so this stays clear
 * of the orchestrator's send throttle (three per account and window), which a suite run twice in
 * a row would otherwise hit.
 */
async function visitSmsAndGoBack(page: Page, theme: Theme) {
  await page.getByRole('button', { name: 'SMS', exact: true }).click()
  await expectDrawnBy(page, theme)
  await expect(page.getByLabel(kc('E-Mail-Adresse'))).not.toHaveValue('')
  await page.getByRole('button', { name: kc('Zurück') }).click()
  await expect(page.getByRole('button', { name: kc('Abbrechen') })).toBeVisible()
}

/** Done: Keycloak hands the browser back to the website with an authorization code. */
async function expectBackAtWebsite(page: Page) {
  await page.waitForURL((url) => url.href.startsWith(`${ORCHESTRATOR}/`) && url.searchParams.has('code'))
}

function visibleButton(page: Page, name: string) {
  return page.getByRole('button', { name, exact: true }).filter({ visible: true })
}

/*
 * A real registration on the website, as a tester does it: the Freischaltcode of the pre-filled
 * test person, the e-mail address confirmed with the demo code, a password, then SMS. The account
 * belongs to the first test person, the one every page pre-fills. Split into its steps, so a test
 * can look at the way back between them.
 */

async function identifyTestPerson(page: Page) {
  await page.goto(loginUrl())
  await page.getByRole('link', { name: kc('Registrieren') }).click()
  await page.getByRole('button', { name: ui('Freischaltcode'), exact: true }).click()
  await visibleButton(page, kc('Weiter zur Freischaltcode-Eingabe')).click()
  await expect(page.getByLabel(kc('Freischaltcode'))).not.toHaveValue('')
  await visibleButton(page, kc('Identifizieren')).click()
}

async function sendEmailCode(page: Page) {
  await expect(page.getByLabel(kc('E-Mail-Adresse'))).not.toHaveValue('')
  await visibleButton(page, kc('Weiter')).click()
}

async function confirmEmailAndSetPassword(page: Page) {
  await page.getByLabel(kc('Bestätigungscode')).fill(await demoValue(page, 'Demo-Code: {wert}'))
  await visibleButton(page, kc('Weiter')).click()

  await visibleButton(page, ui('Passwort')).click()
  registeredPassword = await demoValue(page, 'Demo-Passwort: {wert}')
  await page.getByLabel(kc('Neues Passwort')).fill(registeredPassword)
  await visibleButton(page, kc('Weiter')).click()
}

/** A password is one kind of method; for loa2 the registration asks for another kind, on the web SMS. */
async function sendSmsCode(page: Page) {
  await page.getByLabel(kc('Telefonnummer')).fill('+49 170 1234567')
  await visibleButton(page, kc('Weiter')).click()
}

async function confirmSms(page: Page) {
  await page.getByLabel(kc('SMS-Code')).fill(await demoValue(page, 'Demo-Code: {wert}'))
  await visibleButton(page, kc('Weiter')).click()
  await expectBackAtWebsite(page)
}

async function registerTestPerson(page: Page) {
  await identifyTestPerson(page)
  await sendEmailCode(page)
  await confirmEmailAndSetPassword(page)
  await sendSmsCode(page)
  await confirmSms(page)
}

/**
 * The whole sign-in by e-mail address and password: the demo person is pre-filled, the password is
 * the one the suite registered.
 */
async function signInByPassword(page: Page, theme: Theme) {
  await page.getByRole('button', { name: ui('Passwort'), exact: true }).click()
  await expectDrawnBy(page, theme)
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

for (const theme of THEMES) {
  test(`${MADE_WITH[theme]}: the method selection, the SMS page and back, then a whole sign-in`, async ({ page, request }) => {
    await switchTheme(request, theme)
    await page.goto(loginUrl())
    await expectDrawnBy(page, theme)
    await expect(page.getByRole('button', { name: kc('Abbrechen') })).toBeVisible()
    await visitSmsAndGoBack(page, theme)
    await signInByPassword(page, theme)
  })
}

for (const theme of THEMES) {
  test(`${MADE_WITH[theme]}: "Zurück" on the Freischaltcode page shows the personal details again`, async ({ page, request }) => {
    await switchTheme(request, theme)
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
}

for (const theme of THEMES) {
  test(`${MADE_WITH[theme]}: "Zurück" in a code step of the registration shows the address or number again`, async ({ page, request }) => {
    // A registration of its own: the account from beforeAll would make this one a login.
    await resetDemo(request)
    await switchTheme(request, theme)
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
      await expect(page.getByLabel(kc('Telefonnummer')).filter({ visible: true })).toBeVisible()
      await visibleButton(page, kc('Zurück')).click()
      await expect(page.getByLabel(kc('SMS-Code'))).toBeVisible()
    })
    // Completed, so the tests after this one find the account again.
    await confirmSms(page)
  })
}

for (const theme of THEMES) {
  test(`${MADE_WITH[theme]}: the QR waiting page asks in the background and does not reload`, async ({ page, request }) => {
    await switchTheme(request, theme)
    await page.goto(loginUrl())
    await page.getByRole('button', { name: ui('Mit App anmelden'), exact: true }).click()
    await expectDrawnBy(page, theme)

    const pairingCode = page.locator('.orchestrator-qr-code, .orc-qr-code')
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
}

test('switching at runtime changes the very next page, without a restart', async ({ page, request }) => {
  await switchTheme(request, 'FREEMARKER')
  await page.goto(loginUrl())
  await expectDrawnBy(page, 'FREEMARKER')

  // Mid-login: the next page Keycloak renders comes from the other theme, and the sign-in goes on.
  await switchTheme(request, 'KEYCLOAKIFY')
  await signInByPassword(page, 'KEYCLOAKIFY')
})

test.describe('the loa1 switch picks the first page', () => {
  // The other tests expect the method selection.
  test.afterEach(async ({ request }) => {
    await switchLoa1Login(request, 'ORCHESTRATOR')
  })

  test("KEYCLOAK_PASSWORD: Keycloak's password form", async ({ page, request }) => {
    await switchLoa1Login(request, 'KEYCLOAK_PASSWORD')

    await page.goto(loginUrl())

    await expect(page.locator('input[type="password"]')).toBeVisible()
    await expect(page.getByRole('button', { name: 'SMS', exact: true })).toHaveCount(0)
  })

  test('ORCHESTRATOR: the method selection', async ({ page, request }) => {
    await switchLoa1Login(request, 'KEYCLOAK_PASSWORD')
    await switchLoa1Login(request, 'ORCHESTRATOR')

    await page.goto(loginUrl())

    await expect(page.getByRole('button', { name: 'SMS', exact: true })).toBeVisible()
  })
})
