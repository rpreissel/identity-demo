import { expect, test, type Page } from '@playwright/test'
import { ui, uiPattern, welcomeHeading } from '../e2e/texts'
import { adminHeaders, ORCHESTRATOR, switchLoa1Login } from './admin'
import { kc } from './texts'
import { visibleButton } from './website'

/** The app and the website both run on the orchestrator. */
test.use({ baseURL: ORCHESTRATOR })

// The test registers the test person anew.
test.beforeEach(async ({ request }) => {
  const reset = await request.post(`${ORCHESTRATOR}/orchestrator/admin/demo-reset`, { headers: adminHeaders })
  expect(reset.status()).toBe(200)
  await switchLoa1Login(request, 'ORCHESTRATOR')
})

test.afterAll(async ({ request }) => {
  const reset = await request.post(`${ORCHESTRATOR}/orchestrator/admin/demo-reset`, { headers: adminHeaders })
  expect(reset.ok()).toBeTruthy()
})

/** Clicks the first of [names] the page shows; null when none is there. */
async function clickFirst(page: Page, names: (string | RegExp)[]): Promise<string | null> {
  for (const name of names) {
    const button = page.getByRole('button', typeof name === 'string' ? { name, exact: true } : { name }).first()
    if (await button.isVisible()) {
      await button.click()
      await page.waitForTimeout(500)
      return String(name)
    }
  }
  return null
}

/**
 * An account registered in the app with the device, then SMS added: on the website only SMS works,
 * the device belongs to the app. Its step-up to loa2 therefore has no second method of another kind
 * there, and the way out is identifying again (docs/journeys/web-select-method.md).
 */
async function registerWithDeviceAndSms(page: Page) {
  const welcome = () => page.getByRole('heading', { name: welcomeHeading })
  const biometrics = ui('Mit Biometrie bestätigen')
  await page.goto('/app/?intent=register')
  await page.getByRole('button', { name: uiPattern('Freischaltcode') }).click()
  await page.getByRole('button', { name: ui('Weiter zur Freischaltcode-Eingabe') }).click()
  await page.getByRole('button', { name: ui('Identifizieren') }).click()
  await page.getByRole('button', { name: ui('Code senden') }).waitFor()
  const deviceChoice = new RegExp(`^${ui('Gerät')} `)
  for (let step = 0; step < 16 && !(await welcome().isVisible()); step++) {
    await page.waitForTimeout(500)
    await clickFirst(page, [ui('Code senden'), ui('Code bestätigen'), deviceChoice, ui('Weiter'), biometrics])
  }
  await expect(welcome()).toBeVisible()

  const phone = page.locator('.phone')
  await phone.getByRole('button', { name: uiPattern('Sicherheit') }).first().click()
  await phone.getByRole('button', { name: new RegExp(`^${ui('Anmeldeverfahren')}`) }).first().click()
  await phone.getByRole('button', { name: ui('Weiteres Verfahren hinzufügen') }).click()
  const smsChoice = () => page.getByRole('button', { name: new RegExp(`^${ui('SMS')} `) }).first()
  for (let step = 0; step < 4 && !(await smsChoice().isVisible()); step++) await clickFirst(page, [biometrics, ui('Weiter')])
  await smsChoice().click()
  // The demo fills in the number and the TAN.
  await page.getByRole('button', { name: ui('Code senden'), exact: true }).click()
  await page.getByRole('button', { name: ui('TAN bestätigen'), exact: true }).click()
  await expect(phone.getByRole('button', { name: ui('Weiteres Verfahren hinzufügen') })).toBeVisible()
}

test('a website step-up without a second method there falls back to identifying again', async ({ browser, page: app }) => {
  await registerWithDeviceAndSms(app)

  // The website, in a browser of its own, signed in with SMS: loa1.
  const web = await (await browser.newContext({ ignoreHTTPSErrors: true, locale: 'de-DE' })).newPage()
  await web.goto(`${ORCHESTRATOR}/web/`)
  await web.getByRole('button', { name: ui('Anmelden'), exact: true }).click()
  await web.getByRole('button', { name: ui('SMS'), exact: true }).click()
  await visibleButton(web, kc('Weiter')).click()
  const demoCode = ((await web.getByText(/\d{6}/).first().textContent()) ?? '').replace(/\D/g, '')
  await web.getByRole('textbox', { name: kc('SMS-Code') }).fill(demoCode)
  await visibleButton(web, kc('Weiter')).click()
  const healthData = web.getByRole('button', { name: new RegExp(`^${ui('Gesundheitsdaten')} `) }).first()
  await healthData.click()

  await test.step('the step-up asks to identify again instead of showing no method at all', async () => {
    await web.getByRole('button', { name: ui('Sicher anmelden') }).first().click()
    // The question says why it is asked, not just yes or no.
    await expect(web.getByText(ui(
      'Mit den vorhandenen Anmeldeverfahren ist das geforderte Sicherheitsniveau nicht erreichbar. ' +
        'Sie können sich stattdessen erneut identifizieren, um es direkt zu erreichen.',
    ))).toBeVisible()
    await web.getByRole('button', { name: ui('Erneut identifizieren'), exact: true }).click()
    await expect(web.getByRole('button', { name: ui('Freischaltcode'), exact: true })).toBeVisible()
  })

  await test.step('the identification with the Freischaltcode reaches loa2 and opens the health data', async () => {
    await web.getByRole('button', { name: ui('Freischaltcode'), exact: true }).click()
    await visibleButton(web, kc('Weiter zur Freischaltcode-Eingabe')).click()
    await visibleButton(web, kc('Identifizieren')).click()
    await expect(web.getByRole('heading', { name: ui('Gesundheitsdaten'), level: 1 })).toBeVisible()
    await expect(web.getByRole('region', { name: ui('Sitzung') }).getByText('loa2', { exact: true })).toBeVisible()
  })
})

