import { expect, test, type Page } from '@playwright/test'
import { adminHeaders, ORCHESTRATOR } from './admin'
import { demoValue, loginUrl, registeredPassword, registerTestPerson, resetDemo, visibleButton } from './website'
import { ui } from '../e2e/texts'
import { kc } from './texts'

/**
 * Changing a sign-in method through the required action `orchestrator-manage-methods`, against the
 * real compose stack: the list offers "Ändern" for password and SMS, the password page says that
 * it replaces the current one, and the list comes back with a notice.
 *
 * One browser session: the first test signs in and proves SMS for loa2; the next rides on
 * Keycloak's SSO session, whose restored proofs are recent enough. So the suite sends one SMS
 * after its registration and stays clear of the send throttle.
 */

const MANAGE = 'orchestrator-manage-methods'

function row(page: Page, method: string) {
  return page.locator('.orc-methods li').filter({ hasText: method })
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
})

test('the list offers "Ändern", and a changed password replaces the current one', async ({ page }) => {
  await openList(page)

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

test('backing out of the change leaves the list as it was', async ({ page }) => {
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
