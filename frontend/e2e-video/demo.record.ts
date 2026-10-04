import { expect, test, type Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { ui, uiPattern, welcomeHeading } from '../e2e/texts'
import { kc } from '../e2e-keycloak/texts'

/**
 * Every page is zoomed to 125 % (1600x1100 px show 1280x880 CSS px), keeps 90 px free at the bottom for the
 * caption bar, and can show a full-screen card. All of it is DOM, so it lands in the recording. Page loads are
 * marked as hidden and cut out afterwards (record-demo-video.sh), so the video never shows a half-built page.
 */
const DEMO_CSS = `
  html { zoom: 1.25; }
  body { padding-bottom: 90px !important; }
  .phone { height: 740px !important; }
  #demo-caption { position: fixed; left: 0; right: 0; bottom: 0; z-index: 2147483646; min-height: 72px; padding: 16px 40px;
    box-sizing: border-box; background: rgba(20, 20, 30, 0.92); color: #fff; font: 22px/1.4 system-ui, sans-serif;
    text-align: center; pointer-events: none; }
  #demo-caption:empty { display: none; }
  #demo-title { position: fixed; inset: 0; z-index: 2147483647; display: flex; flex-direction: column; justify-content: center;
    align-items: center; gap: 22px; padding: 40px 60px; box-sizing: border-box; background: #14141e; color: #fff; pointer-events: none;
    font-family: system-ui, sans-serif; text-align: center; }
  #demo-title .kicker { font-size: 24px; letter-spacing: 0.08em; text-transform: uppercase; color: #9fb4ff; }
  #demo-title .title { font-size: 44px; font-weight: 600; max-width: 1000px; line-height: 1.25; }
  #demo-title .steps { font-size: 30px; line-height: 1.6; color: #d6d9e6; max-width: 960px; text-align: left; }
  #demo-title .steps p { margin: 0 0 14px; }
  #demo-title .steps ul { margin: 0; padding-left: 1.2em; }
  #demo-title .steps li { margin: 0 0 12px; }
  #demo-title .steps b { color: #fff; }
  #demo-title .steps.center, #demo-title .steps p.center { text-align: center; }
  #demo-title svg { width: 1080px; height: auto; }
  #demo-title svg text { font-family: system-ui, sans-serif; fill: #fff; }
  #demo-title svg .box { fill: #1f2033; stroke: #7c8bd6; stroke-width: 2; rx: 10; }
  #demo-title svg .core { fill: #232447; stroke: #9fb4ff; stroke-width: 3; rx: 12; }
  #demo-title svg .sim { fill: #1a1a26; stroke: #8d8fa3; stroke-width: 2; stroke-dasharray: 8 6; rx: 10; }
  #demo-title svg .name { font-size: 22px; font-weight: 600; }
  #demo-title svg .sub { font-size: 15px; fill: #c7cbe0; }
  #demo-title svg .edgeLabel { font-size: 15px; fill: #9fb4ff; }`

/** Where a name comes from (docs/07-betrieb.md Abschnitt 3a, ADR-38): one source, read on demand. */
const FLOW = `
<svg viewBox="0 0 1200 330" xmlns="http://www.w3.org/2000/svg">
  <defs><marker id="arrow2" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="8" markerHeight="8" orient="auto-start-reverse">
    <path d="M 0 0 L 10 5 L 0 10 z" fill="#9fb4ff"/></marker></defs>
  <rect class="sim" x="10" y="90" width="260" height="120"/>
  <text class="name" x="140" y="130" text-anchor="middle">Personenverzeichnis</text>
  <text class="sub" x="140" y="158" text-anchor="middle">Die eine Quelle für Name,</text>
  <text class="sub" x="140" y="180" text-anchor="middle">Adresse und Nummern. Simuliert.</text>
  <rect class="core" x="330" y="90" width="320" height="120"/>
  <text class="name" x="490" y="130" text-anchor="middle">Orchestrator</text>
  <text class="sub" x="490" y="158" text-anchor="middle">Führt das Konto. Liest die Person</text>
  <text class="sub" x="490" y="180" text-anchor="middle">im Verzeichnis, wenn sie gebraucht wird.</text>
  <rect class="box" x="710" y="90" width="320" height="120"/>
  <text class="name" x="870" y="130" text-anchor="middle">Keycloak</text>
  <text class="sub" x="870" y="158" text-anchor="middle">Hält keine Kopie. Liest das Konto beim</text>
  <text class="sub" x="870" y="180" text-anchor="middle">Orchestrator, höchstens 60 s im Cache.</text>
  <rect class="box" x="1090" y="60" width="100" height="60"/>
  <text class="sub" x="1140" y="97" text-anchor="middle" font-size="17">App</text>
  <rect class="box" x="1090" y="180" width="100" height="60"/>
  <text class="sub" x="1140" y="217" text-anchor="middle" font-size="17">Website</text>
  <path d="M 270 150 L 330 150" stroke="#9fb4ff" stroke-width="2.5" fill="none" marker-end="url(#arrow2)"/>
  <text class="edgeLabel" x="300" y="138" text-anchor="middle">liest</text>
  <path d="M 650 150 L 710 150" stroke="#9fb4ff" stroke-width="2.5" fill="none" marker-end="url(#arrow2)"/>
  <text class="edgeLabel" x="680" y="138" text-anchor="middle">liest</text>
  <path d="M 1030 130 L 1090 95" stroke="#9fb4ff" stroke-width="2.5" fill="none" marker-end="url(#arrow2)"/>
  <path d="M 1030 170 L 1090 205" stroke="#9fb4ff" stroke-width="2.5" fill="none" marker-end="url(#arrow2)"/>
  <text class="edgeLabel" x="1060" y="154" text-anchor="middle">Token</text>
  <text class="sub" x="600" y="280" text-anchor="middle" font-size="18">Der Name steht nur an einer Stelle. Alle anderen lesen ihn nach. So gibt es keine Widersprüche.</text>
</svg>`

const TITLE_MS = 10_000
const PAUSE_MS = 2_500

let current = ''
let startedAt = 0
/** Seconds since the recording started; the cut script aligns it with the video (first card = calibration). */
const clock = () => (Date.now() - startedAt) / 1000
/** Stretches to cut: page loads, spinners, anything half-built. Open while the last one has no end. */
const hidden: { from: number; to?: number }[] = []
let calibration = 0

function hide() {
  const last = hidden.at(-1)
  if (!last || last.to !== undefined) hidden.push({ from: clock() })
}

function reveal() {
  const last = hidden.at(-1)
  if (last && last.to === undefined) last.to = clock()
}

async function caption(page: Page, text: string, holdMs = 4000) {
  current = text
  await apply(page)
  await page.waitForTimeout(holdMs)
}

async function apply(page: Page) {
  await page.evaluate((text) => {
    ;(window as unknown as { __demoInstall: (t: string) => void }).__demoInstall(text)
  }, current)
}

/** Runs in every new document before its scripts: style and caption bar are there from the first paint. */
function installer(css: string) {
  const w = window as unknown as { __demoInstall: (t: string) => void; __demoCaption: () => Promise<string> }
  w.__demoInstall = (text: string) => {
    if (!document.getElementById('demo-style')) {
      const style = document.createElement('style')
      style.id = 'demo-style'
      style.textContent = css
      document.head.appendChild(style)
    }
    let bar = document.getElementById('demo-caption')
    if (!bar) {
      bar = document.createElement('div')
      bar.id = 'demo-caption'
      document.body.appendChild(bar)
    }
    bar.textContent = text
  }
  const start = () => w.__demoCaption().then((text) => w.__demoInstall(text))
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start)
  else start()
}

type CardOptions = {
  holdMs?: number
  diagram?: 'flow'
  /** Caption for the page behind the card, set while the card still covers it. */
  next?: string
  /** Leave the card up and the video hidden: the next step navigates or shows another card. */
  stay?: boolean
}

/** A full-screen card: kicker, title, optional diagram, and body HTML (own markup only, no user data). */
async function card(page: Page, kicker: string, title: string, bodyHtml: string, options: CardOptions = {}) {
  await page.evaluate(([k, t, b, d]) => {
    document.getElementById('demo-title')?.remove()
    const el = document.createElement('div')
    el.id = 'demo-title'
    el.innerHTML = `<div class="kicker"></div><div class="title"></div>${d}<div class="steps"></div>`
    ;(el.querySelector('.kicker') as HTMLElement).textContent = k
    ;(el.querySelector('.title') as HTMLElement).textContent = t
    ;(el.querySelector('.steps') as HTMLElement).innerHTML = b
    document.body.appendChild(el)
  }, [kicker, title, bodyHtml, options.diagram === 'flow' ? FLOW : ''])
  if (!calibration) calibration = clock()
  await page.waitForTimeout(400)
  reveal()
  await page.waitForTimeout(options.holdMs ?? TITLE_MS)
  if (options.stay) {
    hide()
    return
  }
  current = options.next ?? ''
  await apply(page)
  await page.evaluate(() => document.getElementById('demo-title')?.remove())
}

/** Navigates with the video hidden; [ready] waits for the finished page. Stays hidden when a card follows. */
async function open(page: Page, url: string, ready: () => Promise<unknown>, text = '', keepHidden = false) {
  hide()
  current = text
  await page.goto(url)
  await ready()
  await page.waitForTimeout(800)
  await apply(page)
  if (!keepHidden) reveal()
}

/** A click that leaves the page (form post, redirect): hidden until [ready], then the caption for the new page. */
async function leave(page: Page, click: () => Promise<unknown>, ready: () => Promise<unknown>, text: string) {
  hide()
  current = text
  await click()
  await ready()
  await page.waitForTimeout(800)
  await apply(page)
  reveal()
}

const phone = (page: Page) => page.locator('.phone')

/** Clicks the first visible button among [names], returns which; [captions] replace the pause for known steps. */
async function clickFirst(page: Page, names: (string | RegExp)[], captions: Map<string, [string, number]> = new Map(), captionPage: Page = page): Promise<string | null> {
  for (const name of names) {
    const button = page.getByRole('button', typeof name === 'string' ? { name, exact: true } : { name }).first()
    if (await button.isVisible()) {
      await button.click()
      const text = captions.get(String(name))
      if (text) {
        // The screen changes within a moment; the caption follows at once, not after a pause.
        await page.waitForTimeout(600)
        await caption(captionPage, text[0], text[1])
      } else {
        await page.waitForTimeout(PAUSE_MS)
      }
      return String(name)
    }
  }
  return null
}

async function adminLogin(page: Page) {
  const user = page.locator('#admin-user')
  if (!(await user.isVisible())) return
  // The demo prefills the credentials; fill replaces instead of appending.
  await user.fill('admin')
  await page.locator('#admin-password').fill('admin')
  await page.waitForTimeout(1000)
  await page.getByRole('button', { name: ui('Anmelden') }).click()
  await page.waitForTimeout(2500)
}

test('Aufgaben der Demo im Browser', async ({ page, context }) => {
  startedAt = Date.now()
  hidden.push({ from: 0 })
  await context.exposeFunction('__demoCaption', () => current)
  await context.addInitScript(installer, DEMO_CSS)
  const welcome = () => page.getByRole('heading', { name: welcomeHeading })
  const biometrics = ui('Mit Biometrie bestätigen')
  const loginLoop = async () => {
    await caption(page, 'Anmelden mit diesem Gerät. Der Nachweis ist der Geräteschlüssel, entsperrt mit Biometrie.', 3000)
    await clickFirst(page, [ui('Mit diesem Gerät anmelden')])
    for (let step = 0; step < 10 && !(await welcome().isVisible()); step++) {
      await page.waitForTimeout(800)
      await clickFirst(page, [biometrics, ui('Weiter')])
    }
    await expect(welcome()).toBeVisible({ timeout: 15_000 })
  }

  // Einführung: was gleich kommt; Konzepte und Bewertung erklärt das Erklärvideo
  await open(page, '/', () => page.getByRole('link', { name: ui('In der App registrieren') }).waitFor(), '', true)
  await card(page, 'Identity-Demo', 'Die Demo im Browser',
    '<p class="center">Sieben Aufgaben zum Selbst-Ausprobieren.<br>Was dahintersteckt, erklärt das Erklärvideo.</p>',
    { stay: true })
  await card(page, 'Was gleich zu sehen ist', 'Sieben Aufgaben',
    '<p>1. In der App registrieren<br>2. QR-Code-Anmeldung aktivieren<br>3. Auf der Website anmelden, die App gibt frei<br>'
    + '4. Die Sitzungen in Keycloak ansehen<br>5. Den Vornamen ändern<br>6. Den Journey-Trace ansehen<br>7. Das Konto löschen</p>',
    { holdMs: TITLE_MS + 2000, next: 'Die Willkommensseite der Demo.' })
  await page.waitForTimeout(4000)

  // 1) Registrierung in der App
  await open(page, '/app/?intent=register', () => phone(page).getByRole('button', { name: uiPattern('Freischaltcode') }).waitFor(), '', true)
  await card(page, 'Aufgabe 1', 'In der App registrieren',
    '<p class="center">Ausweisen, E-Mail bestätigen, das Gerät als Anmeldeverfahren einrichten.</p>',
    { next: 'Links die App, rechts die Erklärung der Demo. Zuerst weisen wir uns aus, mit dem Freischaltcode.' })
  await page.waitForTimeout(5000)
  await page.getByRole('button', { name: uiPattern('Freischaltcode') }).click()
  await page.waitForTimeout(600)
  await caption(page, 'Die Demo füllt die Angaben der Testperson aus.', 4000)
  await page.getByRole('button', { name: ui('Weiter zur Freischaltcode-Eingabe') }).click()
  await page.waitForTimeout(600)
  await caption(page, 'Der Freischaltcode kommt per Brief. Die Demo hat ihn schon eingetragen.', 5000)
  await page.getByRole('button', { name: ui('Identifizieren') }).click()
  await page.getByRole('button', { name: ui('Code senden') }).waitFor({ timeout: 15_000 })
  await page.waitForTimeout(600)
  await caption(page, 'Ausgewiesen. Jetzt bestätigen wir die E-Mail-Adresse.', 5000)

  const deviceChoice = new RegExp(`^${ui('Gerät')} `)
  const registrationCaptions = new Map<string, [string, number]>([
    [ui('Code senden'), ['Der Code kommt per E-Mail. Die Demo trägt ihn ein.', 4000]],
    [ui('Code bestätigen'), ['Adresse bestätigt. Jetzt das erste Anmeldeverfahren. Wir wählen „Gerät“: ein Schlüssel auf dem Gerät, geschützt mit PIN oder Biometrie.', 6000]],
    [String(deviceChoice), ['Das Gerät bekommt einen Namen.', 3000]],
    [ui('Weiter'), ['Das Gerät wird entsperrt, hier mit Biometrie.', 3000]],
    [biometrics, ['Das Gerät ist eingerichtet. Damit ist die Registrierung fertig.', 4000]],
  ])
  for (let step = 0; step < 16 && !(await welcome().isVisible()); step++) {
    await page.waitForTimeout(800)
    if (await welcome().isVisible()) break
    await clickFirst(page, [ui('Code senden'), ui('Code bestätigen'), deviceChoice, ui('Weiter'), biometrics], registrationCaptions)
  }
  await expect(welcome()).toBeVisible({ timeout: 15_000 })
  await caption(page, 'Registriert und angemeldet. Dieses Gerät ist mit dem Konto verknüpft.', 5000)

  // 2) Sicherheit: QR-Login aktivieren (direkt nach der Registrierung, die Sitzung steht auf loa2)
  hide()
  await card(page, 'Aufgabe 2', 'QR-Code-Anmeldung aktivieren',
    '<p class="center">Unter „Sicherheit“ ein Verfahren hinzufügen.</p>', { stay: true })
  // Behind the card: open "Sicherheit", then take the card away.
  await phone(page).getByRole('button', { name: uiPattern('Sicherheit') }).first().click()
  await page.waitForTimeout(1200)
  current = 'Die Seite „Sicherheit“ zeigt das Sicherheitsniveau und die Anmeldeverfahren.'
  await apply(page)
  await page.evaluate(() => document.getElementById('demo-title')?.remove())
  reveal()
  await page.waitForTimeout(4500)
  await phone(page).getByRole('button', { name: uiPattern('Anmeldeverfahren') }).first().click()
  await page.waitForTimeout(600)
  await caption(page, 'Eingerichtet ist bisher nur das Gerät.', 3500)
  await phone(page).getByRole('button', { name: ui('Weiteres Verfahren hinzufügen') }).click()
  await page.waitForTimeout(600)
  const qrChoice = () => page.getByRole('button', { name: uiPattern('QR-Code') }).first()
  for (let step = 0; step < 6 && !(await qrChoice().isVisible()); step++) {
    await clickFirst(page, [biometrics, ui('Weiter')], new Map([[biometrics, ['Dafür verlangt die App einen frischen Nachweis.', 3000]]]))
  }
  await caption(page, 'Zur Wahl stehen die Verfahren, die das Konto noch nicht hat.', 4000)
  await qrChoice().click()
  await page.waitForTimeout(600)
  await caption(page, 'Wir aktivieren die Anmeldung per QR-Code.', 3500)
  await page.getByRole('button', { name: ui('Aktivieren'), exact: true }).click()
  const qrActive = () => phone(page).getByRole('button', { name: uiPattern('QR-Code') })
  for (let step = 0; step < 6 && !(await qrActive().isVisible()); step++) {
    await clickFirst(page, [biometrics, ui('Weiter')])
  }
  await expect(qrActive()).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(600)
  await caption(page, 'Aktiv. Auf der Website kann man sich jetzt mit der App anmelden.', 5000)

  // 3) Website: Anmeldung mit der App bestätigen (echtes Keycloak)
  const webLogin = () => page.getByRole('button', { name: ui('Anmelden'), exact: true })
  await open(page, '/web/', () => webLogin().waitFor(), '', true)
  await card(page, 'Aufgabe 3', 'Auf der Website anmelden, die App gibt frei',
    '<p class="center">Echtes Keycloak. Die App gibt die Anmeldung frei.</p>',
    { next: 'Die Website. „Anmelden“ führt zu Keycloak, dem Anmeldedienst der Website.' })
  await page.waitForTimeout(5000)
  const withApp = () => page.getByRole('button', { name: ui('Mit App anmelden'), exact: true })
  await leave(page, () => webLogin().click(), () => withApp().waitFor({ timeout: 20_000 }),
    'Die Anmeldeseite von Keycloak zeigt die Verfahren des Kontos. Wir wählen „Mit der App anmelden“.')
  await page.waitForTimeout(6000)
  const pairing = page.locator('.orchestrator-qr-code, .orc-qr-code')
  await leave(page, () => withApp().click(), () => pairing.waitFor({ timeout: 20_000 }),
    'Die Website zeigt einen QR-Code und einen Pairing-Code. Sie wartet auf die App.')
  await page.waitForTimeout(6000)
  const pairingCode = (await pairing.textContent())?.trim() ?? ''

  // The app confirms in a second tab (its own video, overlaid later): the timing is written down.
  const appCreatedAt = clock()
  const app = await context.newPage()
  await app.goto(`/app/?intent=confirm_peer_login&pairingCode=${encodeURIComponent(pairingCode)}`)
  await app.evaluate(() => document.getElementById('demo-caption')?.remove())
  // The app opens on the fresh proof (unlock the device, then confirm) unless the last login is fresh
  // enough; then it asks for the confirmation right away. Then the code.
  const unlock = () => app.getByRole('button', { name: biometrics, exact: true })
  const approve = () => app.getByRole('button', { name: ui('Bestätigen'), exact: true })
  await unlock().or(approve()).first().waitFor({ timeout: 20_000 })
  await app.waitForTimeout(800)
  const appStartedAt = clock()
  await caption(page, (await unlock().isVisible())
    ? 'Oben rechts die App. Sie hat den QR-Code gelesen. Vor der Freigabe verlangt sie einen neuen Nachweis: das Gerät entsperren.'
    : 'Oben rechts die App. Sie hat den QR-Code gelesen. Die letzte Anmeldung ist frisch genug, sie fragt gleich: Anmeldung im Browser bestätigen?', 5000)
  const codeShown = () => app.getByRole('heading', { name: ui('Code im Browser eingeben') })
  const confirmCaptions = new Map<string, [string, number]>([
    [biometrics, ['Nachweis erbracht. Die App fragt: Anmeldung im Browser bestätigen?', 4500]],
  ])
  for (let step = 0; step < 12 && !(await codeShown().isVisible()); step++) {
    await app.waitForTimeout(800)
    if (await codeShown().isVisible()) break
    // "Bestätigen" makes Keycloak load its code page in the main tab: hidden until that page is there.
    if (await approve().isVisible()) hide()
    await clickFirst(app, [biometrics, ui('Bestätigen'), ui('Weiter')], confirmCaptions, page)
  }
  await expect(codeShown()).toBeVisible({ timeout: 20_000 })
  // Only the digits: the app groups the code for reading, and the demo column shows codes of its own.
  const confirmationCode = ((await app.locator('.phone .code-display__value').first().textContent()) ?? '').replace(/\D/g, '')
  const codeInput = page.locator('#confirmationCode')
  await codeInput.waitFor({ timeout: 20_000 })
  await page.waitForTimeout(800)
  current = `Die App zeigt den Code ${confirmationCode}. Wir geben ihn im Browser ein.`
  await apply(page)
  reveal()
  await page.waitForTimeout(5000)
  await codeInput.pressSequentially(confirmationCode, { delay: 250 })
  await page.waitForTimeout(1500)
  const loggedIn = () => page.getByRole('button', { name: ui('Abmelden'), exact: true })
  await leave(page, () => page.getByRole('button', { name: kc('Weiter'), exact: true }).click(), () => loggedIn().waitFor({ timeout: 30_000 }),
    'Angemeldet auf der Website. Die App hat die Anmeldung freigegeben.')
  await page.waitForTimeout(6000)
  const appEndedAt = clock()
  await app.close()

  // 4) Sitzungen: Keycloak-Konsole und Admin-Seite
  const kcUser = page.locator('#username')
  await open(page, 'https://localhost:8543/admin/master/console/#/Demo/sessions', () => kcUser.waitFor({ timeout: 30_000 }), '', true)
  await card(page, 'Aufgabe 4', 'Die Sitzungen in Keycloak ansehen',
    '<p class="center">Keycloak führt die Sitzungen von App und Website.</p>',
    { next: 'Die Admin-Konsole von Keycloak. Wir melden uns als Administrator an.' })
  await page.waitForTimeout(2000)
  await kcUser.pressSequentially('admin', { delay: 150 })
  await page.locator('#password').pressSequentially('admin', { delay: 150 })
  await page.waitForTimeout(800)
  await leave(page, () => page.locator('#kc-login').click(), async () => {
    await page.getByRole('heading', { name: 'Sessions', level: 1 }).waitFor({ timeout: 30_000 })
    await page.getByRole('link', { name: 'identity-demo-web' }).first().waitFor({ timeout: 20_000 })
    await page.waitForTimeout(1000)
  }, 'Realm „Demo“, Menü „Sessions“. Zwei Sitzungen: die der Website und die der App. Die Sitzung der App hat der Orchestrator geöffnet.')
  await page.waitForTimeout(9000)
  // 5) Personenverzeichnis: Namen ändern
  await open(page, '/personenverzeichnis/', () => page.getByRole('button', { name: ui('Bearbeiten') }).first().waitFor(), '', true)
  await card(page, 'Aufgabe 5', 'Im Personenverzeichnis den Vornamen ändern',
    '<p class="center">Danach zeigen App und Keycloak den neuen Namen.</p>',
    { next: 'Das Personenverzeichnis, ein simuliertes Fremdsystem. Wir bearbeiten die Testperson.' })
  await page.waitForTimeout(5000)
  await page.getByRole('button', { name: ui('Bearbeiten') }).first().click()
  await page.waitForTimeout(1500)
  const vorname = page.locator('#ext-vorname')
  // The field sits at the lower edge; lift it above the caption bar before typing.
  await vorname.evaluate((el) => el.scrollIntoView({ block: 'center', behavior: 'smooth' }))
  await page.waitForTimeout(1000)
  await vorname.fill('')
  await vorname.pressSequentially('Maximilian', { delay: 150 })
  await caption(page, 'Neuer Vorname: Maximilian. Das Verzeichnis meldet die Änderung an das Konto.', 4000)
  await page.getByRole('button', { name: ui('Speichern') }).click()
  await page.waitForTimeout(3000)
  await open(page, '/app/', () => phone(page).getByRole('button', { name: ui('Mit diesem Gerät anmelden') }).waitFor(),
    'In der App erscheint der neue Name bei der nächsten Anmeldung.')
  await page.waitForTimeout(4000)
  await loginLoop()
  await expect(page.getByRole('heading', { name: /Maximilian/ })).toBeVisible({ timeout: 15_000 })
  await caption(page, 'Die App zeigt den Namen aus dem Personenverzeichnis.', 6000)
  hide()
  await card(page, 'So kommt der Name an', 'Eine Quelle, alle lesen nach', '', { holdMs: TITLE_MS, diagram: 'flow', stay: true })
  const kcSearch = page.getByRole('textbox', { name: /search/i }).first()
  await open(page, 'https://localhost:8543/admin/master/console/#/Demo/users', async () => {
    await page.getByRole('heading', { name: 'Users', level: 1 }).waitFor({ timeout: 30_000 })
    await kcSearch.waitFor({ timeout: 20_000 })
    await page.waitForTimeout(800)
  }, 'Die Nutzer in Keycloak. Die Suche findet ein Konto über seine E-Mail-Adresse.')
  await page.waitForTimeout(4000)
  await kcSearch.pressSequentially('max.mustermann@example.com', { delay: 60 })
  await kcSearch.press('Enter')
  const kcUserLink = page.getByRole('link', { name: 'max.mustermann@example.com' }).first()
  await kcUserLink.waitFor({ timeout: 20_000 })
  await page.waitForTimeout(600)
  await caption(page, 'Keycloak zeigt schon den neuen Vornamen. Es hat ihn gerade beim Orchestrator gelesen.', 6000)
  const firstName = page.getByRole('textbox', { name: 'First name' })
  await leave(page, () => kcUserLink.click(), async () => {
    await firstName.waitFor({ timeout: 20_000 })
    await page.waitForTimeout(1000)
  }, 'Die Nutzerdaten in Keycloak. Der Federation-Link zeigt auf den Orchestrator.')
  await page.waitForTimeout(4000)
  await page.getByRole('textbox', { name: 'Last name' }).evaluate((el) => el.scrollIntoView({ block: 'center', behavior: 'smooth' }))
  await page.waitForTimeout(1000)
  await caption(page, 'Vorname, Nachname, E-Mail und die Claims: alles beim Orchestrator gelesen, nichts in Keycloak gespeichert.', 8000)

  // 6) Admin: Journey-Trace
  await open(page, '/admin/#journeytrace', async () => {
    await page.waitForTimeout(1500)
    await adminLogin(page)
    await page.getByText('Maximilian Muster', { exact: false }).first().waitFor({ timeout: 20_000 }).catch(() => undefined)
    await page.waitForTimeout(1500)
  }, '', true)
  await card(page, 'Aufgabe 6', 'Im Journey-Trace den Verlauf ansehen',
    '<p class="center">Jeder Schritt, vom Orchestrator selbst mitgeschrieben.</p>',
    { next: 'Der Journey-Trace. Jeder Schritt mit Zeitpunkt, Kanal und Ergebnis. Alles schreibt der Orchestrator selbst mit. Die App meldet nichts.' })
  await page.waitForTimeout(9000)

  // 7) Konto löschen
  await open(page, '/app/', () => phone(page).getByRole('button', { name: ui('Mit diesem Gerät anmelden') }).waitFor(), '', true)
  await card(page, 'Aufgabe 7', 'Das Konto löschen',
    '<p class="center">Unter „Sicherheit“. Danach kennt das Gerät kein Konto mehr.</p>')
  await loginLoop()
  await phone(page).getByRole('button', { name: uiPattern('Sicherheit') }).first().click()
  await page.waitForTimeout(600)
  await caption(page, 'Unter „Sicherheit“ steht ganz unten „Konto löschen“.', 3500)
  await phone(page).getByRole('button', { name: uiPattern('Konto löschen') }).first().click()
  await page.waitForTimeout(600)
  await caption(page, 'Die App fragt nach und verlangt noch einmal einen Nachweis.', 4000)
  const gone = () => phone(page).getByRole('button', { name: ui('Neues Konto anlegen') })
  const deleteCaptions = new Map<string, [string, number]>([
    [biometrics, ['Nachweis erbracht. Das Konto wird gelöscht.', 3000]],
  ])
  for (let step = 0; step < 14 && !(await gone().isVisible()); step++) {
    await page.waitForTimeout(800)
    await clickFirst(page, [ui('Konto löschen'), biometrics, ui('Weiter')], deleteCaptions)
  }
  await expect(gone()).toBeVisible({ timeout: 15_000 })
  await caption(page, 'Das Konto ist gelöscht. Das Gerät kennt kein Konto mehr. Die Demo beginnt von vorn.', 7000)

  // Ausklang: Verweis auf das Erklärvideo
  hide()
  await card(page, 'Wie es weitergeht', 'Hintergründe im Erklärvideo',
    '<p class="center">Konzepte, Sicherheitsniveaus, Architektur, Stand, Vor- und Nachteile:<br>'
    + '<b>docs/media/erklaervideo.mp4</b></p>',
    { holdMs: TITLE_MS, stay: true })
  // The last frame is the card; nothing after it is kept.

  mkdirSync(test.info().outputDir, { recursive: true })
  writeFileSync(join(test.info().outputDir, 'timing.json'), JSON.stringify({
    calibration,
    app: { created: appCreatedAt, from: appStartedAt, to: appEndedAt },
    hidden: hidden.map((h) => ({ from: h.from, to: h.to ?? null })),
  }, null, 2))
})
