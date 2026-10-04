import { expect, test, type Page } from '@playwright/test'
import { mkdirSync, writeFileSync } from 'node:fs'
import { join } from 'node:path'
import { pv, ui, uiPattern, welcomeHeading } from '../e2e/texts'
import { kc } from '../e2e-keycloak/texts'
import { narration, stopNarrator } from './narrator'

/**
 * Every page is zoomed to 125 % (1600x1100 px show 1280x880 CSS px), keeps 90 px free at the bottom for the
 * caption bar, and can show a full-screen card. All of it is DOM, so it lands in the recording. Page loads are
 * marked as hidden and cut out afterwards (record-demo-video.sh), so the video never shows a half-built page.
 * Every caption is also spoken (narrator.ts): the page stays as it is while the sentence lasts, and the
 * cut script lays the sentences under the video at the times logged here.
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

const TITLE_MS = 6_000
const PAUSE_MS = 2_000

let current = ''
let startedAt = 0
/** Seconds since the recording started; the cut script aligns it with the video (first card = calibration). */
const clock = () => (Date.now() - startedAt) / 1000
/** Stretches to cut: page loads, spinners, anything half-built. Open while the last one has no end. */
const hidden: { from: number; to?: number }[] = []
/** What is said when, on the spec's clock; the cut script mixes it into the video. */
const spokenAt: { at: number; wav: string }[] = []
let calibration = 0

function hide() {
  const last = hidden.at(-1)
  if (!last || last.to !== undefined) hidden.push({ from: clock() })
}

function reveal() {
  const last = hidden.at(-1)
  if (last && last.to === undefined) last.to = clock()
}

/** Says [text] now and waits until the sentence is over, at least [minMs]. The screen must be visible. */
async function speak(page: Page, text: string, minMs = 0) {
  const voice = await narration(text)
  spokenAt.push({ at: clock(), wav: voice.wav })
  await page.waitForTimeout(Math.max(minMs, voice.seconds * 1000 + 600))
}

/** Shows [text] in the caption bar and says it. */
async function caption(page: Page, text: string, minMs = 0) {
  current = text
  await apply(page)
  await speak(page, text, minMs)
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
  /** Caption for the page behind the card, said once the card is gone. */
  next?: string
  /** Leave the card up and the video hidden: the next step navigates or shows another card. */
  stay?: boolean
}

/** A full-screen card: kicker, title, optional diagram, and body HTML (own markup only, no user data); [say] is spoken. */
async function card(page: Page, kicker: string, title: string, bodyHtml: string, say: string, options: CardOptions = {}) {
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
  await speak(page, say, options.holdMs ?? TITLE_MS)
  if (options.stay) {
    hide()
    return
  }
  current = options.next ?? ''
  await apply(page)
  await page.evaluate(() => document.getElementById('demo-title')?.remove())
  if (options.next) await speak(page, options.next)
}

/** Navigates with the video hidden; [ready] waits for the finished page, then [text] is said. Stays hidden when a card follows. */
async function open(page: Page, url: string, ready: () => Promise<unknown>, text = '', keepHidden = false) {
  hide()
  current = text
  await page.goto(url)
  await ready()
  await page.waitForTimeout(800)
  await apply(page)
  if (keepHidden) return
  reveal()
  if (text) await speak(page, text)
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
  await speak(page, text)
}

const phone = (page: Page) => page.locator('.phone')

/** Clicks the first visible button among [names], returns which; [captions] replace the pause for known steps. */
async function clickFirst(page: Page, names: (string | RegExp)[], captions: Map<string, string> = new Map(), captionPage: Page = page): Promise<string | null> {
  for (const name of names) {
    const button = page.getByRole('button', typeof name === 'string' ? { name, exact: true } : { name }).first()
    if (await button.isVisible()) {
      await button.click()
      const text = captions.get(String(name))
      if (text) {
        // The screen changes within a moment; the caption follows at once, not after a pause.
        await page.waitForTimeout(600)
        await caption(captionPage, text)
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

/** The confirmation of a web login in the app (a second tab, overlaid later as picture-in-picture): the code it shows. */
async function confirmInApp(page: Page, app: Page, pairingCode: string, biometrics: string): Promise<{ code: string; shownAt: number }> {
  await app.goto(`/app/?intent=confirm_peer_login&pairingCode=${encodeURIComponent(pairingCode)}`)
  await app.evaluate(() => document.getElementById('demo-caption')?.remove())
  // The app asks for a fresh proof (unlock the device) unless the last one is recent; then it asks right away.
  const unlock = () => app.getByRole('button', { name: biometrics, exact: true })
  const approve = () => app.getByRole('button', { name: ui('Bestätigen'), exact: true })
  await unlock().or(approve()).first().waitFor({ timeout: 20_000 })
  await app.waitForTimeout(800)
  const shownAt = clock()
  await caption(page, (await unlock().isVisible())
    ? 'Oben rechts die App. Sie hat den QR-Code gelesen. Bevor sie etwas freigibt, verlangt sie einen frischen Nachweis: Wir entsperren das Gerät.'
    : 'Oben rechts die App. Sie hat den QR-Code gelesen und fragt, ob wir diese Anmeldung im Browser selbst begonnen haben.')
  const codeShown = () => app.getByRole('heading', { name: ui('Code im Browser eingeben') })
  const confirmCaptions = new Map<string, string>([
    [biometrics, 'Nachweis erbracht. Jetzt fragt die App, ob wir die Anmeldung im Browser bestätigen.'],
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
  const code = ((await app.locator('.phone .code-display__value').first().textContent()) ?? '').replace(/\D/g, '')
  return { code, shownAt }
}

test('Aufgaben der Demo im Browser', async ({ page, context }) => {
  startedAt = Date.now()
  hidden.push({ from: 0 })
  await context.exposeFunction('__demoCaption', () => current)
  await context.addInitScript(installer, DEMO_CSS)
  const welcome = () => page.getByRole('heading', { name: welcomeHeading })
  const biometrics = ui('Mit Biometrie bestätigen')
  const loginLoop = async () => {
    await caption(page, 'Wir melden uns mit diesem Gerät an. Der Nachweis ist der Geräteschlüssel, entsperrt mit Biometrie.')
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
    'Willkommen zur Demo. Wir spielen die Aufgaben der Willkommensseite einmal durch, mit echtem Keycloak. '
    + 'Warum das System so gebaut ist, erklärt das Erklärvideo.',
    { stay: true })
  await card(page, 'Was gleich zu sehen ist', 'Sieben Aufgaben',
    '<p>1. In der App registrieren<br>2. QR-Code-Anmeldung und Passwort einrichten<br>'
    + '3. Auf der Website anmelden, für die Gesundheitsdaten bestätigt die App<br>4. Den Vornamen ändern<br>'
    + '5. Einen Vorgang mit Einmalkennwort erledigen<br>6. Den Journey-Trace ansehen<br>7. Das Konto löschen</p>',
    'Sieben Aufgaben: registrieren, weitere Verfahren einrichten, auf der Website anmelden und das Sicherheitsniveau anheben, '
    + 'einen Namen ändern, einen Vorgang mit Einmalkennwort, der Journey-Trace und zum Schluss das Konto löschen.',
    { next: 'Das ist die Willkommensseite der Demo. Sie führt durch dieselben Aufgaben.' })

  // 1) Registrierung in der App
  await open(page, '/app/?intent=register', () => phone(page).getByRole('button', { name: uiPattern('Freischaltcode') }).waitFor(), '', true)
  await card(page, 'Aufgabe 1', 'In der App registrieren',
    '<p class="center">Identifizieren, E-Mail bestätigen, das Gerät als Anmeldeverfahren einrichten.</p>',
    'Aufgabe eins: Wir registrieren uns in der App. Dabei identifizieren wir uns, bestätigen die E-Mail-Adresse und richten das Gerät als Anmeldeverfahren ein.',
    { next: 'Links die App, so wie sie auf einem Smartphone liefe. Rechts erklärt die Demo, was im Hintergrund passiert. '
      + 'Zuerst identifizieren wir uns, hier mit dem Freischaltcode aus einem Brief der Versicherung.' })
  await page.getByRole('button', { name: uiPattern('Freischaltcode') }).click()
  await page.waitForTimeout(600)
  await caption(page, 'Die Demo füllt die Angaben der Testperson aus: Name, Geburtsdatum und Versichertennummer.')
  await page.getByRole('button', { name: ui('Weiter zur Freischaltcode-Eingabe') }).click()
  await page.waitForTimeout(600)
  await caption(page, 'Den Freischaltcode schickt das Personenverzeichnis per Brief. Die Demo hat ihn schon eingetragen.')
  await page.getByRole('button', { name: ui('Identifizieren') }).click()
  await page.getByRole('button', { name: ui('Code senden') }).waitFor({ timeout: 15_000 })
  await page.waitForTimeout(600)
  await caption(page, 'Identifiziert. Der Orchestrator legt das Konto an. Als Nächstes bestätigen wir die E-Mail-Adresse.')

  const deviceChoice = new RegExp(`^${ui('Gerät')} `)
  const registrationCaptions = new Map<string, string>([
    [ui('Code senden'), 'Der Bestätigungscode kommt per E-Mail. In der Demo landet sie im Briefkasten, und die Demo trägt den Code ein.'],
    [ui('Code bestätigen'), 'Adresse bestätigt. Jetzt das erste Anmeldeverfahren. Wir wählen „Gerät“: einen Schlüssel, der das Gerät nie verlässt, geschützt mit PIN oder Biometrie.'],
    [String(deviceChoice), 'Das Gerät bekommt einen Namen, damit man es später wiedererkennt.'],
    [ui('Weiter'), 'Zum Einrichten wird das Gerät entsperrt, hier mit Biometrie.'],
    [biometrics, 'Das Gerät ist eingerichtet. Damit ist die Registrierung fertig.'],
  ])
  for (let step = 0; step < 16 && !(await welcome().isVisible()); step++) {
    await page.waitForTimeout(800)
    if (await welcome().isVisible()) break
    await clickFirst(page, [ui('Code senden'), ui('Code bestätigen'), deviceChoice, ui('Weiter'), biometrics], registrationCaptions)
  }
  await expect(welcome()).toBeVisible({ timeout: 15_000 })
  await caption(page, 'Registriert und angemeldet, auf Sicherheitsniveau zwei: Identifizierung und Gerät. Rechts oben stehen Niveau und Verfahren dieser Sitzung.')

  // 2) Sicherheit: QR-Login und Passwort (direkt nach der Registrierung, die Sitzung steht auf loa2)
  hide()
  await card(page, 'Aufgabe 2', 'QR-Code-Anmeldung und Passwort einrichten',
    '<p class="center">Unter „Sicherheit“ weitere Verfahren hinzufügen.</p>',
    'Aufgabe zwei: Wir richten zwei weitere Verfahren ein. Die Anmeldung per QR-Code, mit der die App eine Anmeldung im Browser freigibt, und ein Passwort.',
    { stay: true })
  // Behind the card: open "Sicherheit", then take the card away.
  await phone(page).getByRole('button', { name: uiPattern('Sicherheit') }).first().click()
  await page.waitForTimeout(1200)
  current = 'Die Seite „Sicherheit“ zeigt das Sicherheitsniveau und die eingerichteten Anmeldeverfahren.'
  await apply(page)
  await page.evaluate(() => document.getElementById('demo-title')?.remove())
  reveal()
  await speak(page, current)
  await phone(page).getByRole('button', { name: uiPattern('Anmeldeverfahren') }).first().click()
  await page.waitForTimeout(600)
  await caption(page, 'Eingerichtet ist bisher nur das Gerät.')
  const freshProof = new Map([[biometrics, 'Wer Verfahren ändert, braucht einen frischen Nachweis. Die App lässt das Gerät noch einmal entsperren.']])
  await phone(page).getByRole('button', { name: ui('Weiteres Verfahren hinzufügen') }).click()
  await page.waitForTimeout(600)
  const qrChoice = () => page.getByRole('button', { name: uiPattern('QR-Code') }).first()
  for (let step = 0; step < 6 && !(await qrChoice().isVisible()); step++) {
    await clickFirst(page, [biometrics, ui('Weiter')], freshProof)
  }
  await caption(page, 'Zur Wahl stehen die Verfahren, die das Konto noch nicht hat. Wir aktivieren die Anmeldung per QR-Code.')
  await qrChoice().click()
  await page.waitForTimeout(600)
  await page.getByRole('button', { name: ui('Aktivieren'), exact: true }).click()
  const qrActive = () => phone(page).getByRole('button', { name: uiPattern('QR-Code') })
  for (let step = 0; step < 6 && !(await qrActive().isVisible()); step++) {
    await clickFirst(page, [biometrics, ui('Weiter')])
  }
  await expect(qrActive()).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(600)
  await caption(page, 'Aktiv. Jetzt noch das Passwort.')
  await phone(page).getByRole('button', { name: ui('Weiteres Verfahren hinzufügen') }).click()
  await page.waitForTimeout(600)
  // "Passwort" exactly: KOBIL's description mentions a password, too.
  const passwordChoice = () => page.getByRole('button', { name: new RegExp(`^${ui('Passwort')} `) }).first()
  for (let step = 0; step < 6 && !(await passwordChoice().isVisible()); step++) {
    await clickFirst(page, [biometrics, ui('Weiter')], freshProof)
  }
  await passwordChoice().click()
  await page.waitForTimeout(600)
  await caption(page, 'Benutzername ist die bestätigte E-Mail-Adresse. Die Demo schlägt ein Passwort vor.')
  await page.getByRole('button', { name: ui('Einrichten'), exact: true }).click()
  const passwordActive = () => phone(page).getByRole('button', { name: new RegExp(`^${ui('Passwort')}`) })
  for (let step = 0; step < 6 && !(await passwordActive().isVisible()); step++) {
    await page.waitForTimeout(800)
    await clickFirst(page, [biometrics, ui('Weiter')])
  }
  await expect(passwordActive()).toBeVisible({ timeout: 15_000 })
  await page.waitForTimeout(600)
  await caption(page, 'Drei Verfahren sind eingerichtet: das Gerät, die Anmeldung per QR-Code und das Passwort.')

  // 3) Website: mit Passwort auf loa1, Step-up für die Gesundheitsdaten mit der App (echtes Keycloak)
  const webLogin = () => page.getByRole('button', { name: ui('Anmelden'), exact: true })
  await open(page, '/web/', () => webLogin().waitFor(), '', true)
  await card(page, 'Aufgabe 3', 'Auf der Website anmelden, für die Gesundheitsdaten bestätigt die App',
    '<p class="center">Mit dem Passwort auf Niveau 1, für die Gesundheitsdaten Niveau 2.</p>',
    'Aufgabe drei: die Website. Hier meldet echtes Keycloak an. Wir melden uns mit dem Passwort an, das ist Sicherheitsniveau eins. '
    + 'Für die Gesundheitsdaten reicht das nicht. Dann bestätigt die App.',
    { next: 'Das Kundenportal der Versicherung. „Anmelden“ leitet zu Keycloak weiter, dem Anmeldedienst der Website.' })
  const passwordMethod = () => page.getByRole('button', { name: ui('Passwort'), exact: true })
  await leave(page, () => webLogin().click(), () => passwordMethod().waitFor({ timeout: 20_000 }),
    'Keycloak zeigt die Verfahren, die der Orchestrator anbietet. Wir wählen das Passwort.')
  const passwordField = page.getByRole('textbox', { name: kc('Passwort') })
  await leave(page, () => passwordMethod().click(), () => passwordField.waitFor({ timeout: 20_000 }),
    'E-Mail-Adresse und Passwort. Die Demo hat die Adresse schon eingetragen, wir tippen das Passwort.')
  await passwordField.pressSequentially('Demo1234!', { delay: 120 })
  await page.waitForTimeout(800)
  const healthTile = () => page.getByRole('button', { name: new RegExp(`^${ui('Gesundheitsdaten')} `) }).first()
  await leave(page, () => page.getByRole('button', { name: kc('Weiter'), exact: true }).click(), () => healthTile().waitFor({ timeout: 30_000 }),
    'Angemeldet, auf Sicherheitsniveau eins: ein einzelnes Verfahren. Die Gesundheitsdaten verlangen mehr.')
  await healthTile().click()
  const secure = () => page.getByRole('button', { name: ui('Sicher anmelden') }).first()
  await secure().waitFor({ timeout: 15_000 })
  await page.waitForTimeout(600)
  await caption(page, 'Für die Gesundheitsdaten braucht es Sicherheitsniveau zwei. „Sicher anmelden“ startet einen Step-up.')
  const pairing = page.locator('.orchestrator-qr-code, .orc-qr-code')
  await leave(page, () => secure().click(), () => pairing.waitFor({ timeout: 20_000 }),
    'Keycloak verlangt einen zweiten Nachweis anderer Art. Es bietet an, mit der App zu bestätigen, und zeigt dafür einen QR-Code.')
  const pairingCode = (await pairing.textContent())?.trim() ?? ''

  const appCreatedAt = clock()
  const app = await context.newPage()
  const { code: confirmationCode, shownAt: appStartedAt } = await confirmInApp(page, app, pairingCode, biometrics)
  const codeInput = page.locator('#confirmationCode')
  await codeInput.waitFor({ timeout: 20_000 })
  await page.waitForTimeout(800)
  current = `Die App zeigt den Code ${confirmationCode}. Wir geben ihn im Browser ein. So ist sicher, dass App und Browser zusammengehören.`
  await apply(page)
  reveal()
  await speak(page, 'Die App zeigt einen Code. Wir geben ihn im Browser ein. So ist sicher, dass App und Browser zusammengehören.')
  await codeInput.pressSequentially(confirmationCode, { delay: 250 })
  await page.waitForTimeout(1200)
  await leave(page, () => page.getByRole('button', { name: kc('Weiter'), exact: true }).click(),
    () => page.getByRole('heading', { name: ui('Gesundheitsdaten') }).waitFor({ timeout: 30_000 }),
    'Jetzt auf Sicherheitsniveau zwei: Passwort und App, zwei Verfahren verschiedener Art. Die Gesundheitsdaten sind offen.')
  const appEndedAt = clock()
  await app.close()

  // 4) Personenverzeichnis: Namen ändern
  await open(page, '/personenverzeichnis/', () => page.getByRole('button', { name: pv('Bearbeiten') }).first().waitFor(), '', true)
  await card(page, 'Aufgabe 4', 'Im Personenverzeichnis den Vornamen ändern',
    '<p class="center">Danach zeigt die App den neuen Namen.</p>',
    'Aufgabe vier: Die Stammdaten der Versicherten liegen im Personenverzeichnis, einem simulierten Fremdsystem. '
    + 'Wir ändern dort den Vornamen und sehen, wie er in der App ankommt.',
    { next: 'Das Personenverzeichnis. Wir bearbeiten unsere Testperson Max Muster.' })
  await page.getByRole('button', { name: pv('Bearbeiten') }).first().click()
  await page.waitForTimeout(1500)
  const vorname = page.locator('#ext-vorname')
  // The field sits at the lower edge; lift it above the caption bar before typing.
  await vorname.evaluate((el) => el.scrollIntoView({ block: 'center', behavior: 'smooth' }))
  await page.waitForTimeout(1000)
  await vorname.fill('')
  await vorname.pressSequentially('Maximilian', { delay: 150 })
  await caption(page, 'Neuer Vorname: Maximilian. Beim Speichern meldet das Verzeichnis die Änderung an das Konto.')
  await page.getByRole('button', { name: pv('Speichern') }).click()
  await page.waitForTimeout(2000)
  await open(page, '/app/', () => phone(page).getByRole('button', { name: ui('Mit diesem Gerät anmelden') }).waitFor(),
    'In der App erscheint der neue Name bei der nächsten Anmeldung.')
  await loginLoop()
  await expect(page.getByRole('heading', { name: /Maximilian/ })).toBeVisible({ timeout: 15_000 })
  await caption(page, 'Die App zeigt den neuen Namen, so wie er im Personenverzeichnis steht.')
  hide()
  await card(page, 'So kommt der Name an', 'Eine Quelle, alle lesen nach', '',
    'So kommt der Name an: Das Personenverzeichnis meldet die Änderung an den Orchestrator. App und Keycloak lesen dort nach. '
    + 'Keycloak hält keine eigene Kopie der Konten.',
    { diagram: 'flow', stay: true })

  // 5) Vorgang mit Einmalkennwort: Einladung, Brief, Anmeldung ohne Konto
  const invitePerson = page.locator('#inv-person')
  await open(page, '/personenverzeichnis/#einladungen', () => invitePerson.waitFor(), '', true)
  await card(page, 'Aufgabe 5', 'Einen Vorgang mit Einmalkennwort erledigen',
    '<p class="center">Ohne Konto: Ein Brief mit Einmalkennwort genügt für genau diesen Vorgang.</p>',
    'Aufgabe fünf: Nicht jeder hat ein Konto. Für einzelne Vorgänge schickt die Versicherung einen Brief mit einem Einmalkennwort. '
    + 'Damit meldet man sich auf der Website an, aber nur für diesen einen Vorgang.',
    { next: 'Im Personenverzeichnis stellt die Versicherung eine Einladung aus. Diesmal für Erika Beispiel, die kein Konto hat.' })
  const erika = (await invitePerson.locator('option', { hasText: 'Erika Beispiel' }).first().textContent()) ?? ''
  await invitePerson.selectOption({ label: erika })
  await page.waitForTimeout(800)
  const issue = page.getByRole('button', { name: pv('Ausstellen und Brief versenden') })
  await issue.evaluate((el) => el.scrollIntoView({ block: 'center', behavior: 'smooth' }))
  await page.waitForTimeout(800)
  await caption(page, 'Der Vorgang ist eine Beitragsrückerstattung, auf Sicherheitsniveau eins.')
  await issue.click()
  await page.waitForTimeout(1200)
  await caption(page, 'Ausgestellt. Das Verzeichnis speichert nur einen Hash. Das Einmalkennwort im Klartext steht allein im Brief.')
  await open(page, '/briefkasten/', () => page.getByRole('cell', { name: 'Erika Beispiel' }).first().waitFor(),
    'Der Briefkasten der Demo, hier landet alles, was an Testpersonen verschickt wird. Ganz oben der Brief an Erika mit dem Einmalkennwort.')
  const inviteTile = () => page.getByRole('button', { name: ui('Mit Einmalkennwort anmelden') })
  const webLogout = () => page.getByRole('button', { name: ui('Abmelden'), exact: true }).first()
  await open(page, '/web/', () => webLogout().or(inviteTile()).first().waitFor(), '', true)
  reveal()
  // Max's web session from task 3 may have run out by now; then the portal is already signed out.
  if (await webLogout().isVisible()) {
    await caption(page, 'Auf der Website ist noch Max angemeldet. Wir melden ihn ab, denn eine Keycloak-Sitzung gehört genau einer Person.')
    await leave(page, () => webLogout().click(), () => inviteTile().waitFor({ timeout: 30_000 }),
      'Abgemeldet. Im Portal gibt es den Weg „Mit Einmalkennwort anmelden“.')
  } else {
    await caption(page, 'Das Kundenportal. Hier gibt es den Weg „Mit Einmalkennwort anmelden“.')
  }
  const inviteMethod = () => page.getByRole('button', { name: ui('Einmalkennwort'), exact: true })
  await leave(page, () => inviteTile().click(), () => inviteMethod().waitFor({ timeout: 20_000 }),
    'Keycloak bietet wieder die Verfahren an. Wir wählen „Einmalkennwort“.')
  await leave(page, () => inviteMethod().click(), () => page.getByLabel(kc('Einmalkennwort'), { exact: true }).waitFor({ timeout: 20_000 }),
    'Versichertennummer und Einmalkennwort aus dem Brief. Die Demo übernimmt beides aus der offenen Einladung.')
  const processHeading = ui('Vorgang: {vorgang}').replace('{vorgang}', 'Beitragsrückerstattung')
  await leave(page, () => page.getByRole('button', { name: kc('Anmelden'), exact: true }).click(),
    () => page.getByRole('heading', { name: processHeading }).waitFor({ timeout: 30_000 }),
    'Angemeldet als Erika Beispiel, auf Sicherheitsniveau eins, aber nur für diesen Vorgang. Ein Konto gibt es dafür nicht.')
  await page.getByRole('button', { name: ui('Vorgang beenden') }).evaluate((el) => el.scrollIntoView({ block: 'center', behavior: 'smooth' }))
  await page.waitForTimeout(800)
  await caption(page, 'Ist der Vorgang erledigt, meldet das Fachsystem ihn beim Personenverzeichnis ab. Hier spielt die Seite das Fachsystem.')
  await leave(page, () => page.getByRole('button', { name: ui('Vorgang beenden') }).click(), () => inviteTile().waitFor({ timeout: 30_000 }),
    'Der Vorgang ist abgeschlossen. Das Einmalkennwort gilt nicht mehr, und Keycloak hat die Sitzung beendet.')

  // 6) Admin: Journey-Trace
  await open(page, '/admin/#journeytrace', async () => {
    await page.waitForTimeout(1500)
    await adminLogin(page)
    await page.getByText('Maximilian Muster', { exact: false }).first().waitFor({ timeout: 20_000 }).catch(() => undefined)
    await page.waitForTimeout(1500)
  }, '', true)
  await card(page, 'Aufgabe 6', 'Im Journey-Trace den Verlauf ansehen',
    '<p class="center">Jeder Schritt, vom Orchestrator selbst mitgeschrieben.</p>',
    'Aufgabe sechs: der Journey-Trace auf der Admin-Seite.',
    { next: 'Hier steht jeder Schritt jeder Journey: Zeitpunkt, Zustand, Tool und die Entscheidung des Orchestrators. '
      + 'Die App meldet nichts davon, der Orchestrator schreibt alles selbst mit.' })
  await page.waitForTimeout(2000)

  // 7) Konto löschen
  await open(page, '/app/', () => phone(page).getByRole('button', { name: ui('Mit diesem Gerät anmelden') }).waitFor(), '', true)
  await card(page, 'Aufgabe 7', 'Das Konto löschen',
    '<p class="center">Unter „Sicherheit“. Danach kennt das Gerät kein Konto mehr.</p>',
    'Aufgabe sieben: Wir löschen das Konto wieder. Das geht in der App unter „Sicherheit“.')
  await loginLoop()
  await phone(page).getByRole('button', { name: uiPattern('Sicherheit') }).first().click()
  await page.waitForTimeout(600)
  await caption(page, 'Unter „Sicherheit“ steht ganz unten „Konto löschen“.')
  await phone(page).getByRole('button', { name: uiPattern('Konto löschen') }).first().click()
  await page.waitForTimeout(600)
  await caption(page, 'Die App fragt nach und verlangt noch einmal einen Nachweis.')
  const gone = () => phone(page).getByRole('button', { name: ui('Neues Konto anlegen') })
  const deleteCaptions = new Map<string, string>([[biometrics, 'Nachweis erbracht. Das Konto wird gelöscht.']])
  for (let step = 0; step < 14 && !(await gone().isVisible()); step++) {
    await page.waitForTimeout(800)
    await clickFirst(page, [ui('Konto löschen'), biometrics, ui('Weiter')], deleteCaptions)
  }
  await expect(gone()).toBeVisible({ timeout: 15_000 })
  await caption(page, 'Das Konto ist gelöscht. Das Gerät kennt kein Konto mehr, und die Demo beginnt von vorn.', 3000)

  // Ausklang: Verweis auf das Erklärvideo
  hide()
  await card(page, 'Wie es weitergeht', 'Hintergründe im Erklärvideo',
    '<p class="center">Konzepte, Sicherheitsniveaus, Architektur, Stand, Vor- und Nachteile:<br>'
    + '<b>docs/media/erklaervideo.mp4</b></p>',
    'Das waren die Aufgaben der Demo. Warum das System so gebaut ist, welche Sicherheitsniveaus es gibt und wie weit es ist, zeigt das Erklärvideo.',
    { stay: true })
  // The last frame is the card; nothing after it is kept.
  stopNarrator()

  mkdirSync(test.info().outputDir, { recursive: true })
  writeFileSync(join(test.info().outputDir, 'timing.json'), JSON.stringify({
    calibration,
    app: { created: appCreatedAt, from: appStartedAt, to: appEndedAt },
    hidden: hidden.map((h) => ({ from: h.from, to: h.to ?? null })),
    narration: spokenAt,
  }, null, 2))
})
