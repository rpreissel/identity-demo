import { expect, test, type APIRequestContext, type Page } from '@playwright/test'
import { ui } from '../e2e/texts'
import { parseJwtPayload } from '../src/jwt'
import { MADE_WITH, ORCHESTRATOR, switchLoa1Login, switchTheme, THEMES } from './admin'
import { kc } from './texts'

/**
 * Process access by one-time password against the real compose stack
 * (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md): the register issues an invitation, the
 * person signs in on the website with number and password, the tokens carry the process, the
 * business system ends the process, and the session ends with it. Needs the whole stack running
 * (`podman compose up -d`), like login-theme.spec.ts.
 */

/** Seeded persons (demo_seed): Max and Erika are insured, Paula is known by her Partnernummer only. */
const MAX = { personId: 'P000000001', kvnr: 'A123456789' }
const ERIKA = { personId: 'P000000002', kvnr: 'B987654321' }
const PAULA = { personId: 'P000000004' }

/** A Keycloak user id from a federation with a fixed UUID as its component id. */
const FEDERATED_UUID_ID = /^f:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}:[0-9a-f]{64}$/

/** The register issues an invitation and sends the letter; the answer is that letter. */
async function issue(request: APIRequestContext, personId: string, vorgang: string, niveau: 'loa1' | 'loa2') {
  const validUntil = new Date(Date.now() + 30 * 24 * 3600 * 1000).toISOString()
  const response = await request.post(`${ORCHESTRATOR}/mock-personenverzeichnis/personen/${personId}/einladungen`, {
    data: { vorgang, niveau, gueltigBis: validUntil },
  })
  expect(response.status()).toBe(201)
  const letter = await response.json()
  return { invitation: letter.einladungId as string, code: letter.code as string }
}

/** From the website's tile to the one-time password page in Keycloak. */
async function openInvitePage(page: Page, tile: 'invite' | 'secure' = 'invite') {
  await page.goto(`${ORCHESTRATOR}/web/`)
  const start = tile === 'invite' ? ui('Mit Einmalkennwort anmelden') : ui('Sicher anmelden')
  await page.getByRole('button', { name: start }).click()
  await page.getByRole('button', { name: ui('Einmalkennwort'), exact: true }).click()
  await expect(page.getByLabel(kc('Einmalkennwort'), { exact: true })).toBeVisible()
}

async function submitInvite(page: Page, number: { kvnr?: string }, code: string) {
  await page.getByLabel(kc('Versichertennummer')).fill(number.kvnr ?? '')
  await page.getByLabel(kc('Einmalkennwort'), { exact: true }).fill(code)
  await page.getByRole('button', { name: kc('Anmelden') }).click()
}

/** The website's tokens, decoded - what a business service would read. */
async function accessClaims(page: Page): Promise<Record<string, unknown>> {
  const accessToken = await page.evaluate(() => JSON.parse(sessionStorage.getItem('web-kanal-tokens') ?? 'null')?.accessToken as string)
  return parseJwtPayload(accessToken) ?? {}
}

function processHeading(name: string) {
  return ui('Vorgang: {vorgang}').replace('{vorgang}', name)
}

test.beforeAll(async ({ request }) => {
  await switchLoa1Login(request, 'ORCHESTRATOR')
})

test.afterAll(async ({ request }) => {
  await switchTheme(request, 'FREEMARKER')
})

for (const theme of THEMES) {
  test(`${MADE_WITH[theme]}: sign in with a one-time password, end the process, and the password is spent`, async ({ page, request }) => {
    await switchTheme(request, theme)
    const letter = await issue(request, MAX.personId, 'beitragsrueckerstattung', 'loa1')

    await test.step('sign in with the one-time password', async () => {
      await openInvitePage(page)
      await expect(page.getByText(kc('Erstellt mit {technik}', { technik: MADE_WITH[theme] }))).toBeVisible()
      // Separators and case do not count.
      await submitInvite(page, MAX, letter.code.toLowerCase().replace(/-/g, ' '))
      await expect(page.getByRole('heading', { name: processHeading('Beitragsrückerstattung') })).toBeVisible()
    })

    await test.step('the tokens carry the process', async () => {
      const claims = await accessClaims(page)
      expect(claims.process).toBe('beitragsrueckerstattung')
      expect(claims.invitation).toBe(letter.invitation)
      expect(claims.sub).toMatch(FEDERATED_UUID_ID)
      expect(claims.sub).toContain(letter.invitation)
      expect(claims.orchestrator_account_id).toBeUndefined()
      expect(claims.acr).toBe('loa1')
      expect(claims.amr).toEqual(['invite'])
      expect(claims.person_id).toBe(MAX.personId)
    })

    await test.step('ending the process ends the session', async () => {
      // The page stands in for the business system and ends the process at the register.
      await page.getByRole('button', { name: ui('Vorgang beenden') }).click()
      await expect(page.getByText(ui('Der Vorgang ist abgeschlossen, Keycloak hat die Sitzung beendet.'))).toBeVisible({ timeout: 15_000 })
    })

    await test.step('the same password again opens nothing', async () => {
      await openInvitePage(page)
      await submitInvite(page, MAX, letter.code)
      await expect(page.getByText(ui('Nummer oder Einmalkennwort ungueltig'))).toBeVisible()
    })
  })
}

test.describe('signed in on the website', () => {
  // Ends the Keycloak session the test opened.
  test.afterEach(async ({ page }) => {
    await page.getByRole('button', { name: ui('Abmelden') }).first().click()
  })

  test('the demo picker lists open invitations and fills the Partnernummer of a partner', async ({ page, request }) => {
    await switchTheme(request, 'FREEMARKER')
    const letter = await issue(request, PAULA.personId, 'bonusprogramm', 'loa2')

    await openInvitePage(page)
    const picker = page.getByLabel(kc('Einladung übernehmen'))
    const option = picker.locator('option', { hasText: 'Paula Schulz – Bonusprogramm' }).first()
    await picker.selectOption({ label: (await option.textContent()) ?? '' })
    await expect(page.getByLabel(kc('Versichertennummer'))).toHaveValue('')
    await expect(page.locator('#partnerNumber')).toHaveValue(PAULA.personId)
    await expect(page.getByLabel(kc('Einmalkennwort'), { exact: true })).toHaveValue(letter.code)
    await page.getByRole('button', { name: kc('Anmelden') }).click()

    await expect(page.getByRole('heading', { name: processHeading('Bonusprogramm') })).toBeVisible()
    expect((await accessClaims(page)).acr).toBe('loa2')
  })
})

test('an invitation below the level the login asks for opens nothing', async ({ page, request }) => {
  await switchTheme(request, 'FREEMARKER')
  const letter = await issue(request, ERIKA.personId, 'adressbestaetigung', 'loa1')

  // "Sicher anmelden" asks for loa2; the invitation carries loa1.
  await openInvitePage(page, 'secure')
  await submitInvite(page, ERIKA, letter.code)
  await expect(page.getByText(ui('Dieses Einmalkennwort genuegt dem verlangten Sicherheitsniveau nicht'))).toBeVisible()
  expect(page.url()).not.toContain(`${ORCHESTRATOR}/web/`)
})
