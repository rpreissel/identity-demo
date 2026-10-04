import { expect, test } from '@playwright/test'
import { completeRegistration } from '../e2e/journey'
import { ui, uiPattern } from '../e2e/texts'
import { adminHeaders, ORCHESTRATOR, switchLoa1Login } from './admin'
import { kc } from './texts'

const KEYCLOAK = process.env.KEYCLOAK_URL ?? 'https://localhost:8543'

/** The app runs on the orchestrator; completeRegistration navigates relative to it. */
test.use({ baseURL: ORCHESTRATOR })

/** The web login as the demo website starts it, loa1 on the orchestrator's method selection. */
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

test.beforeAll(async ({ request }) => {
  const reset = await request.post(`${ORCHESTRATOR}/orchestrator/admin/demo-reset`, { headers: adminHeaders })
  expect(reset.status()).toBe(200)
  await switchLoa1Login(request, 'ORCHESTRATOR')
})

test.afterAll(async ({ request }) => {
  const reset = await request.post(`${ORCHESTRATOR}/orchestrator/admin/demo-reset`, { headers: adminHeaders })
  expect(reset.ok()).toBeTruthy()
})

/**
 * The whole QR sign-in across both channels, in two browsers (docs/03-tool-architektur.md,
 * auth-qr-lookup and approve-qr): the website shows a pairing code, the freshly signed-in app
 * takes the code and approves, then shows a confirmation code that the website needs
 * to finish. The waiting page itself is covered in login-theme.spec.ts; this follows the code through.
 */
test('the website signs in with the app: pairing code into the app, confirmation code back', async ({ browser, page: app }) => {
  // The app: an account with SMS and password, then the QR opt-in under "Sicherheit".
  await completeRegistration(app)
  const phone = app.locator('.phone')
  await phone.getByRole('button', { name: uiPattern('Sicherheit') }).first().click()
  await phone.getByRole('button', { name: new RegExp(`^${ui('Anmeldeverfahren')}`) }).first().click()
  await phone.getByRole('button', { name: ui('Weiteres Verfahren hinzufügen') }).click()
  await phone.getByRole('button', { name: uiPattern('Anmeldung per QR-Code') }).first().click()
  await phone.getByRole('button', { name: ui('Aktivieren'), exact: true }).click()
  await expect(phone.getByRole('button', { name: ui('Weiteres Verfahren hinzufügen') })).toBeVisible()
  await phone.getByRole('button', { name: ui('Zurück') }).click()
  await phone.getByRole('button', { name: ui('Zurück') }).click()

  // The website, in a browser of its own: the pairing code it waits with.
  const web = await (await browser.newContext({ ignoreHTTPSErrors: true, locale: 'de-DE' })).newPage()
  await web.goto(loginUrl())
  await web.getByRole('button', { name: ui('Mit App anmelden'), exact: true }).click()
  const pairingCode = ((await web.locator('.orchestrator-qr-code, .orc-qr-code').textContent()) ?? '').trim()
  expect(pairingCode).not.toBe('')

  // The app approves. Its registration is moments old, so no further proof is asked for: the code comes next.
  await phone.getByRole('button', { name: new RegExp(`^${ui('Anmeldung im Browser bestätigen')}`) }).first().click()
  await phone.locator('#pairingCode').fill(pairingCode)
  await phone.getByRole('button', { name: ui('Weiter'), exact: true }).click()
  await phone.getByRole('button', { name: ui('Bestätigen'), exact: true }).click()
  const confirmationCode = ((await phone.locator('.code-display__value').textContent()) ?? '').replace(/\s/g, '')
  expect(confirmationCode).toMatch(/^\d{6}$/)

  // The website takes the code and hands the browser back with an authorization code.
  await web.getByLabel(kc('Code aus der App')).fill(confirmationCode)
  await web.getByRole('button', { name: kc('Weiter') }).click()
  await web.waitForURL((url) => url.href.startsWith(`${ORCHESTRATOR}/`) && url.searchParams.has('code'))

  // "Fertig" ends the approval in the app.
  await phone.getByRole('button', { name: ui('Fertig'), exact: true }).click()
  await web.context().close()
})
