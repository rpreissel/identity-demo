import { expect, test } from '@playwright/test'
import { adminHeaders, ORCHESTRATOR, switchTheme } from './admin'
import { demoValue, loginUrl, registeredPassword, registerTestPerson, resetDemo, visibleButton } from './website'
import { ui } from '../e2e/texts'
import { kc } from './texts'

/**
 * The required action for managing methods asks for a fresh proof once the session's latest one
 * is older than `identity.policy.self-service-max-age` (docs/04-orchestrierung.md #8). Five minutes
 * by default, which no suite waits for: this spec runs only against a stack started with a short
 * limit, and is told how short:
 *
 *   SELF_SERVICE_MAX_AGE=PT10S podman compose up -d
 *   SELF_SERVICE_MAX_AGE_SECONDS=10 npm run test:e2e:keycloak
 */
const maxAgeSeconds = Number(process.env.SELF_SERVICE_MAX_AGE_SECONDS ?? 0)

test.skip(maxAgeSeconds <= 0, 'needs a stack with a short identity.policy.self-service-max-age (see the spec)')

test.beforeAll(async ({ browser, request }) => {
  await resetDemo(request)
  await switchTheme(request, 'FREEMARKER')
  const context = await browser.newContext()
  await registerTestPerson(await context.newPage())
  await context.close()
})

test.afterAll(async ({ request }) => {
  const reset = await request.post(`${ORCHESTRATOR}/orchestrator/admin/demo-reset`, { headers: adminHeaders })
  expect(reset.ok()).toBeTruthy()
})

test('changing the password on an older session asks for a fresh proof first', async ({ page }) => {
  test.setTimeout(90_000)
  const passwordRow = page.locator('.orchestrator-method-row').filter({ hasText: ui('Passwort') })
  const change = passwordRow.getByRole('button', { name: kc('Ändern'), exact: true })

  // Sign in, and reach loa2 through a first change: the password, then SMS on top.
  await page.goto(loginUrl('orchestrator-manage-methods'))
  await page.getByRole('button', { name: ui('Passwort'), exact: true }).click()
  await page.getByLabel(kc('Passwort'), { exact: true }).fill(registeredPassword)
  await page.getByRole('button', { name: kc('Weiter') }).click()
  await change.click()
  await page.getByLabel(kc('SMS-Code')).fill(await demoValue(page, 'Demo-Code: {wert}'))
  await visibleButton(page, kc('Weiter')).click()
  await page.getByLabel(kc('Neues Passwort')).fill(registeredPassword)
  await visibleButton(page, kc('Weiter')).click()
  await expect(page.getByText(kc('Anmeldeverfahren geändert.'))).toBeVisible()

  // Older than the limit: the level still holds, the proof is no longer fresh.
  await page.waitForTimeout((maxAgeSeconds + 1) * 1000)
  await change.click()
  await expect(page.getByText(ui('Ihr letzter Nachweis liegt länger zurück. Bitte bestätigen Sie zuerst eines Ihrer Anmeldeverfahren.'))).toBeVisible()
  await page.getByRole('button', { name: ui('Passwort'), exact: true }).click()
  await page.getByLabel(kc('Passwort'), { exact: true }).fill(registeredPassword)
  await page.getByRole('button', { name: kc('Weiter') }).click()

  // The proof carries out the wish: the password page, saying that it replaces.
  await expect(page.getByText(kc('Das neue Passwort ersetzt Ihr bisheriges, sobald Sie fertig sind.'))).toBeVisible()
})
