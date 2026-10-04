import { expect, test, type Page } from '@playwright/test'
import { adminHeaders, MADE_WITH, ORCHESTRATOR, switchTheme, THEMES, type Theme } from './admin'
import { demoValue, loginUrl, registeredPassword, registerTestPerson, resetDemo, visibleButton } from './website'
import { ui } from '../e2e/texts'
import { kc } from './texts'

/**
 * Changing a sign-in method through the required action `orchestrator-manage-methods`, against the
 * real compose stack and in both themes: the list offers "Ändern" for password and SMS, the
 * password page says that it replaces the current one, and the list comes back with a notice.
 *
 * One browser session for both themes: the first run signs in and proves SMS for loa2; the second
 * rides on Keycloak's SSO session, whose restored proofs are recent enough. So the suite sends one
 * SMS after its registration and stays clear of the send throttle.
 */

const MANAGE = 'orchestrator-manage-methods'

function row(page: Page, method: string) {
  return page.locator('.orchestrator-method-row, .orc-methods li').filter({ hasText: method })
}

async function expectList(page: Page) {
  await expect(page.getByRole('button', { name: kc('Neues Anmeldeverfahren hinzufügen') })).toBeVisible()
}

/** Signs in with the password where the login asks for it; with a live SSO session the list comes at once. */
async function openList(page: Page) {
  await page.goto(loginUrl(MANAGE))
  const password = page.getByRole('button', { name: ui('Passwort'), exact: true })
  const add = page.getByRole('button', { name: kc('Neues Anmeldeverfahren hinzufügen') })
  await expect(password.or(add).first()).toBeVisible()
  if (await add.isVisible()) return
  await password.click()
  await page.getByLabel(kc('Passwort'), { exact: true }).fill(registeredPassword)
  await page.getByRole('button', { name: kc('Weiter') }).click()
  await expectList(page)
}

/** "Ändern" on the password row, through whatever the journey asks first, up to the password page. */
async function startPasswordChange(page: Page) {
  await row(page, ui('Passwort')).getByRole('button', { name: kc('Ändern'), exact: true }).click()

  const newPassword = page.getByLabel(kc('Neues Passwort'))
  const smsCode = page.getByLabel(kc('SMS-Code'))
  await expect(newPassword.or(smsCode).first()).toBeVisible()
  // An identified account manages its methods at loa2: the password alone asks for SMS on top.
  if (await smsCode.isVisible()) {
    await smsCode.fill(await demoValue(page, 'Demo-Code: {wert}'))
    await visibleButton(page, kc('Weiter')).click()
  }
  await expect(newPassword).toBeVisible()
}

test.beforeAll(async ({ browser, request }) => {
  await resetDemo(request)
  const context = await browser.newContext()
  await registerTestPerson(await context.newPage())
  await context.close()
})

test.afterAll(async ({ request }) => {
  const reset = await request.post(`${ORCHESTRATOR}/orchestrator/admin/demo-reset`, { headers: adminHeaders })
  expect(reset.ok()).toBeTruthy()
  await switchTheme(request, 'FREEMARKER')
})

test('the list offers "Ändern", and a changed password replaces the current one - in both themes', async ({ page, request }) => {
  for (const theme of THEMES as readonly Theme[]) {
    await test.step(MADE_WITH[theme], async () => {
      await switchTheme(request, theme)
      await openList(page)
      await expect(page.getByText(kc('Erstellt mit {technik}', { technik: MADE_WITH[theme] }))).toBeVisible()

      // Password and SMS can be changed in place.
      await expect(row(page, ui('Passwort')).getByRole('button', { name: kc('Ändern'), exact: true })).toBeVisible()
      await expect(row(page, 'SMS').getByRole('button', { name: kc('Ändern'), exact: true })).toBeVisible()

      await startPasswordChange(page)
      await expect(page.getByText(kc('Das neue Passwort ersetzt Ihr bisheriges, sobald Sie fertig sind.'))).toBeVisible()
      await page.getByLabel(kc('Neues Passwort')).fill(registeredPassword)
      await visibleButton(page, kc('Weiter')).click()

      await expectList(page)
      await expect(page.getByText(kc('Anmeldeverfahren geändert.'))).toBeVisible()
      await expect(row(page, ui('Passwort'))).toHaveCount(1)
    })
  }
})

test('backing out of the change leaves the list as it was', async ({ page, request }) => {
  await switchTheme(request, 'FREEMARKER')
  await openList(page)
  await startPasswordChange(page)

  // "Zurück" leaves the tool, not the wish: the one way to change it is offered again.
  await visibleButton(page, kc('Zurück')).click()
  await expect(page.getByRole('button', { name: ui('Passwort'), exact: true })).toBeVisible()
  await page.getByRole('button', { name: kc('Abbrechen') }).click()

  await expectList(page)
  await expect(page.getByText(kc('Abgebrochen.'))).toBeVisible()
  await expect(row(page, ui('Passwort'))).toHaveCount(1)
})
