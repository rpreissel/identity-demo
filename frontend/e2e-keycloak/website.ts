import { expect, type APIRequestContext, type Page } from '@playwright/test'
import { adminHeaders, ORCHESTRATOR } from './admin'
import { ui } from '../e2e/texts'
import { kc } from './texts'

/**
 * What the Keycloak suites share: the demo's reset, the web login as the website starts it, and a
 * real registration of the first test person through the login pages.
 */

export const KEYCLOAK = process.env.KEYCLOAK_URL ?? 'https://localhost:8543'
/** The password the suite's own registration set: the demo password the page offered. */
export let registeredPassword = ''

/** The demo's start: no accounts. */
export async function resetDemo(request: APIRequestContext) {
  const reset = await request.post(`${ORCHESTRATOR}/orchestrator/admin/demo-reset`, { headers: adminHeaders })
  expect(reset.status()).toBe(200)
}

/** The demo value a page shows next to its field, e.g. "Demo-Code: 123456" (ADR-28). */
export async function demoValue(page: Page, template: string): Promise<string> {
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
export function loginUrl(kcAction?: string): string {
  const url = new URL(`${KEYCLOAK}/realms/Demo/protocol/openid-connect/auth`)
  url.searchParams.set('client_id', 'identity-demo-web')
  url.searchParams.set('redirect_uri', `${ORCHESTRATOR}/`)
  url.searchParams.set('response_type', 'code')
  url.searchParams.set('scope', 'openid')
  url.searchParams.set('acr_values', '1')
  url.searchParams.set('code_challenge_method', 'S256')
  url.searchParams.set('code_challenge', 'E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM')
  if (kcAction) url.searchParams.set('kc_action', kcAction)
  return url.toString()
}

/** Done: Keycloak hands the browser back to the website with an authorization code. */
export async function expectBackAtWebsite(page: Page) {
  await page.waitForURL((url) => url.href.startsWith(`${ORCHESTRATOR}/`) && url.searchParams.has('code'))
}

export function visibleButton(page: Page, name: string) {
  return page.getByRole('button', { name, exact: true }).filter({ visible: true })
}

/*
 * A real registration on the website, as a tester does it: the Freischaltcode of the pre-filled
 * test person, the e-mail address confirmed with the demo code, a password, then SMS. The account
 * belongs to the first test person, the one every page pre-fills. Split into its steps, so a test
 * can look at the way back between them.
 */

export async function identifyTestPerson(page: Page) {
  await page.goto(loginUrl())
  await page.getByRole('link', { name: kc('Registrieren') }).click()
  await page.getByRole('button', { name: ui('Freischaltcode'), exact: true }).click()
  await visibleButton(page, kc('Weiter zur Freischaltcode-Eingabe')).click()
  await expect(page.getByLabel(kc('Freischaltcode'))).not.toHaveValue('')
  await visibleButton(page, kc('Identifizieren')).click()
}

export async function sendEmailCode(page: Page) {
  await expect(page.getByLabel(kc('E-Mail-Adresse'))).not.toHaveValue('')
  await visibleButton(page, kc('Weiter')).click()
}

export async function confirmEmailAndSetPassword(page: Page) {
  await page.getByLabel(kc('Bestätigungscode')).fill(await demoValue(page, 'Demo-Code: {wert}'))
  await visibleButton(page, kc('Weiter')).click()

  await visibleButton(page, ui('Passwort')).click()
  registeredPassword = await demoValue(page, 'Demo-Passwort: {wert}')
  await page.getByLabel(kc('Neues Passwort')).fill(registeredPassword)
  await visibleButton(page, kc('Weiter')).click()
}

/** A password is one kind of method; for loa2 the registration asks for another kind, on the web SMS. */
export async function sendSmsCode(page: Page) {
  // Exact: the consent's label mentions the phone number too.
  await page.getByLabel(kc('Telefonnummer'), { exact: true }).fill('+49 170 1234567')
  // enroll-sms@2 asks for consent first; an earlier version has no such box.
  const consent = page.getByRole('checkbox', { name: kc('Ich willige ein, dass meine Telefonnummer gespeichert wird und ich Codes per SMS erhalte.') })
  if (await consent.isVisible()) await consent.check()
  await visibleButton(page, kc('Weiter')).click()
}

export async function confirmSms(page: Page) {
  await page.getByLabel(kc('SMS-Code')).fill(await demoValue(page, 'Demo-Code: {wert}'))
  await visibleButton(page, kc('Weiter')).click()
  await expectBackAtWebsite(page)
}

export async function registerTestPerson(page: Page) {
  await identifyTestPerson(page)
  await sendEmailCode(page)
  await confirmEmailAndSetPassword(page)
  await sendSmsCode(page)
  await confirmSms(page)
}

