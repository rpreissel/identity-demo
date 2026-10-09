import { useEffect, useState, type ReactNode } from 'react'
import '../../App.css'
import {
  describeError,
  fetchDemoSessions,
  fetchServerInfo,
  resetDemoPublic,
  type ActiveSessionsReport,
  type OperationsInfo,
  type ServerInfo,
} from '../../api'
import { ActiveSessionsView } from '../../components/ActiveSessionsView'
import { DeveloperTools } from '../../components/DeveloperTools'
import { markAsStartWindow } from '../../startWindow'
import { ChannelNav } from '../../components/ChannelNav'
import { ADMIN_TAB, APP_TAB, REGISTER_TAB, WEB_TAB, AREA_LINKS, areaLabel, type Area } from '../../areas'
import { t } from '../../texts'
import { Tx } from '../../Tx'
import { useHashTab } from '../../useHashTab'

const TAB_KEYS = ['loslegen', 'begriffe', 'status'] as const
type Tab = (typeof TAB_KEYS)[number]
const TABS: { key: Tab; label: string }[] = [
  { key: 'loslegen', label: t('Loslegen') },
  { key: 'begriffe', label: t('Begriffe') },
  { key: 'status', label: t('Server-Status') },
]

const REPO = 'https://github.com/rpreissel/identity-demo'

/**
 * The landing page (docs/10-frontend.md #6): no channel logic, no DPoP key. It reads only the
 * public server status; every switch lives on the admin page. Links open each app in a named
 * tab. No `rel="noopener"`: it would break the named-target lookup, and same-origin has no
 * tabnabbing risk.
 */
export function WelcomeApp() {
  const [tab, setTab] = useHashTab<Tab>(TAB_KEYS, 'loslegen')
  // Whether the Web channel exists at all (only with the `keycloak` profile) - null while loading,
  // then its links stay links until we know otherwise.
  const [keycloak, setKeycloak] = useState<boolean | null>(null)

  // This tab is where "← Startseite" in every app tab switches back to (startWindow.ts).
  useEffect(markAsStartWindow, [])

  useEffect(() => {
    fetchServerInfo()
      .then((info) => setKeycloak(info.keycloak != null))
      .catch(() => setKeycloak(null))
  }, [])

  return (
    <div className="web-shell channel-start">
      <ChannelNav tabs={TABS} sub={tab} onSelectTab={setTab} />
      <main className="start-page">
        {tab === 'loslegen' && <GetStarted keycloak={keycloak} />}
        {tab === 'begriffe' && <Glossary />}
        {tab === 'status' && <ServerStatus />}
      </main>
    </div>
  )
}

/** One task to try: in which area, what to do, and the link that opens the area's tab. */
function Task({ area, children, action }: { area: Area; children: ReactNode; action: ReactNode }) {
  return (
    <li className="start-task">
      <span className={`start-area-name start-area-name--${area}`}>
        <span className={`shell-dot shell-dot--${area}`} aria-hidden="true" />
        {areaLabel(area)}
      </span>
      <p>{children}</p>
      {action}
    </li>
  )
}

function OpenLink({ href, target, label }: { href: string; target: string; label: string }) {
  return (
    <a className="start-go" href={href} target={target}>
      {label}
    </a>
  )
}

/** What each area plays, and whether it is real or simulated. */
const AREA_HINTS: Record<Area, { hint: () => string; real: boolean }> = {
  app: { hint: () => t('Registrieren, anmelden, Verfahren einrichten. Daneben erklärt die Demo jeden Schritt.'), real: true },
  web: { hint: () => t('Anmelden wie im Kundenportal, auch per QR-Code mit der App. Dahinter steht ein echtes Keycloak.'), real: true },
  pv: { hint: () => t('Die Testpersonen und ihre Freischaltcodes. Ein fremdes System, nur simuliert.'), real: false },
  mail: { hint: () => t('Briefe, SMS und E-Mails an die Testpersonen, mit den Codes darin. Die Seite der Empfänger, nur simuliert.'), real: false },
  admin: { hint: () => t('Jeden Schritt jeder Journey verfolgen, Verfahren sperren, die Demo zurücksetzen.'), real: true },
}

function RealTag({ real }: { real: boolean }) {
  return <span className={real ? 'tag tag-real' : 'tag tag-sim'}>{real ? t('Echt') : t('Simuliert')}</span>
}

/** Invites to play: one first action, then a few tasks, then what each area plays. */
function GetStarted({ keycloak }: { keycloak: boolean | null }) {
  const webMissing = (
    <span className="task-note">
      <Tx text="Nur mit Keycloak ({befehl})" befehl={<code>./gradlew bootRunKc</code>} />
    </span>
  )
  return (
    <>
      <section className="start-hero">
        <div className="start-hero-text">
          <span className="tag tag-real">{t('Echter Orchestrator · simulierte Umgebung')}</span>
          <h1>{t('Registrieren und anmelden – und dabei hinter die Kulissen schauen.')}</h1>
          <p className="start-lead">{t('Legen Sie ein Konto an, melden Sie sich an wie bei einer Krankenkasse, und schauen Sie dabei hinter die Kulissen.')}</p>
          <div className="start-actions">
            <a className="button button-large" href="/app/?intent=register" target={APP_TAB}>
              {t('In der App registrieren')}
            </a>
            {keycloak !== false && (
              <a className="button button-large secondary" href="/web/" target={WEB_TAB}>
                {t('Website öffnen')}
              </a>
            )}
          </div>
          <p className="start-note">
            {t(
              'Registrieren Sie sich mit einer Testperson. Wählen Sie als Anmeldeverfahren „Gerät“: Dann ist die App an ' +
                'dieses Gerät gebunden, und Sie melden sich hier künftig ohne Passwort an. Das dauert etwa zwei Minuten, ' +
                'und alles, was Sie eingeben, steht schon im Formular oder in der Spalte daneben.',
            )}
          </p>
        </div>
        <ol className="start-steps">
          <li>
            <span className="start-step-no">01</span>
            <span>
              <strong>{t('In der App registrieren')}</strong>
              <span>{t('Identifizieren, E-Mail-Adresse bestätigen, „Gerät“ als Anmeldeverfahren wählen.')}</span>
            </span>
          </li>
          <li>
            <span className="start-step-no">02</span>
            <span>
              <strong>{t('Auf der Website anmelden')}</strong>
              <span>{t('Die Gesundheitsdaten verlangen mehr: Sie bestätigen die Anmeldung in der App.')}</span>
            </span>
          </li>
          <li>
            <span className="start-step-no">03</span>
            <span>
              <strong>{t('Nachvollziehen')}</strong>
              <span>{t('In der Verwaltung jeden Schritt Ihrer Journey verfolgen.')}</span>
            </span>
          </li>
        </ol>
      </section>

      <section className="start-section">
        <div className="start-section-head">
          <h2>{t('Danach ausprobieren')}</h2>
          <span>{t('Jede Aufgabe zeigt einen anderen Teil der Demo.')}</span>
        </div>
        <ul className="start-tasks">
          <Task area="app" action={<OpenLink href="/app/" target={APP_TAB} label={t('App öffnen')} />}>
            {t('In der App unter „Sicherheit“ die Anmeldung per QR-Code aktivieren und ein Passwort vergeben.')}
          </Task>
          <Task area="web" action={keycloak === false ? webMissing : <OpenLink href="/web/" target={WEB_TAB} label={t('Website öffnen')} />}>
            {t('Auf der Website mit dem Passwort anmelden und die Gesundheitsdaten öffnen: Dafür bestätigen Sie die Anmeldung in der App.')}
          </Task>
          <Task area="pv" action={<OpenLink href="/personenverzeichnis/" target={REGISTER_TAB} label={t('Personenverzeichnis öffnen')} />}>
            {t('Im Personenverzeichnis Ihren Namen ändern und sehen, dass die App ihn übernimmt.')}
          </Task>
          <Task area="pv" action={<OpenLink href="/personenverzeichnis/#einladungen" target={REGISTER_TAB} label={t('Einladung ausstellen')} />}>
            {t('Im Personenverzeichnis einen Brief mit Einmalkennwort verschicken und damit auf der Website einen Vorgang erledigen, ganz ohne Konto.')}
          </Task>
          <Task area="admin" action={<OpenLink href="/admin/#journeytrace" target={ADMIN_TAB} label={t('Verwaltung öffnen')} />}>
            {t('In der Verwaltung verfolgen, welche Schritte Ihre Journey durchlaufen hat.')}
          </Task>
          <Task area="app" action={<OpenLink href="/app/" target={APP_TAB} label={t('App öffnen')} />}>
            {t('In der App unter „Sicherheit“ das Konto löschen und von vorn anfangen.')}
          </Task>
        </ul>
      </section>

      <section className="start-section">
        <h2>{t('Die Bereiche der Demo')}</h2>
        <ul className="start-areas">
          {AREA_LINKS.map((link) =>
            link.key === 'web' && keycloak === false ? (
              <li key={link.key}>
                <div className={`start-area start-area--${link.key} start-area--off`} aria-disabled="true">
                  <strong>{t('Website nicht verfügbar')}</strong>
                  <span>
                    <Tx
                      text="Nur mit echtem Keycloak - Server mit Profil {profil} starten ({befehl})"
                      profil={<code>keycloak</code>}
                      befehl={<code>./gradlew bootRunKc</code>}
                    />
                  </span>
                </div>
              </li>
            ) : (
              <li key={link.key}>
                <a className={`start-area start-area--${link.key}`} href={link.href} target={link.target}>
                  <strong>{areaLabel(link.key)}</strong>
                  <span>{AREA_HINTS[link.key].hint()}</span>
                  <RealTag real={AREA_HINTS[link.key].real} />
                </a>
              </li>
            ),
          )}
        </ul>
      </section>

      <section className="start-section start-realsim">
        <div className="start-panel">
          <RealTag real />
          <ul>
            <li>{t('Der Orchestrator: Journeys, Konten, Verfahren, Tokens')}</li>
            <li>{t('Die DPoP-Signaturen - der Geräteschlüssel entsteht per WebCrypto im Browser')}</li>
            <li>
              <Tx text="Keycloak und der Website-Login (OIDC mit PKCE) - nur mit Profil {profil}" profil={<code>keycloak</code>} />
            </li>
            <li>{t('Die Sicherheitsniveaus (loa1 bis loa3) und der Step-up')}</li>
          </ul>
        </div>
        <div className="start-panel">
          <RealTag real={false} />
          <ul>
            <li>{t('Das Smartphone - ein Browser-Tab')}</li>
            <li>{t('SMS- und E-Mail-Versand - die Codes stehen im Briefkasten')}</li>
            <li>{t('Der Brief mit dem Freischaltcode - ebenfalls im Briefkasten')}</li>
            <li>{t('Das Auslesen der eID-Karte und die Dienstleister Nect und KOBIL')}</li>
            <li>{t('Das Personenverzeichnis selbst')}</li>
          </ul>
        </div>
      </section>

      <DemoReset />
    </>
  )
}

/**
 * The reset without admin login (DemoSessionsController). Before it runs, it shows who is still
 * using the demo, because the reset ends their sessions; confirmed inline, not with window.confirm.
 */
function DemoReset() {
  const [step, setStep] = useState<'idle' | 'loading' | 'confirm' | 'running'>('idle')
  const [report, setReport] = useState<ActiveSessionsReport | null>(null)
  const [result, setResult] = useState<string | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function ask() {
    setStep('loading')
    setResult(null)
    setError(null)
    try {
      setReport(await fetchDemoSessions())
      setStep('confirm')
    } catch (err) {
      setError(describeError(t('Sitzungen laden fehlgeschlagen'), err))
      setStep('idle')
    }
  }

  async function reset() {
    setStep('running')
    try {
      const r = await resetDemoPublic()
      setResult(t('Demo zurückgesetzt. Gelöschte Konten: {geloescht}. Beendete Sitzungen: {beendet}.', { geloescht: r.deletedAccounts, beendet: r.endedSessions }))
    } catch (err) {
      setError(describeError(t('Zurücksetzen fehlgeschlagen'), err))
    }
    setReport(null)
    setStep('idle')
  }

  const active = report ? activeCount(report) : 0
  return (
    <section className="start-reset">
      <h2>{t('Demo zurücksetzen')}</h2>
      <p>
        {t(
          'Löscht alle Konten und stellt die Einstellungen der Demo auf den Anfang zurück. ' +
            'Die Testpersonen im Personenverzeichnis bleiben, sie können sich danach neu registrieren.',
        )}
      </p>
      {error && <p className="error-card">{error}</p>}
      {result && <p className="hint">{result}</p>}
      {step === 'confirm' || step === 'running' ? (
        <>
          {report && active > 0 ? (
            <>
              <p className="hint">
                {active === 1
                  ? t('Eine Sitzung ist gerade aktiv, zum Beispiel in einem offenen App-Tab. Das Zurücksetzen beendet sie.')
                  : t('{anzahl} Sitzungen sind gerade aktiv, zum Beispiel in einem offenen App-Tab. Das Zurücksetzen beendet sie.', {
                      anzahl: active,
                    })}
              </p>
              <ActiveSessionsView report={report} />
            </>
          ) : (
            <p>{t('Gerade ist keine Sitzung aktiv.')}</p>
          )}
          <div className="form-actions">
            <button className="destructive" onClick={reset} disabled={step === 'running'}>
              {t('Jetzt zurücksetzen')}
            </button>
            <button className="secondary" onClick={() => setStep('idle')} disabled={step === 'running'}>
              {t('Abbrechen')}
            </button>
          </div>
        </>
      ) : (
        <button className="secondary" onClick={ask} disabled={step === 'loading'}>
          {t('Demo zurücksetzen…')}
        </button>
      )}
    </section>
  )
}

/** The orchestrator's live channels; Keycloak's count only if there are none (they overlap). */
function activeCount(report: ActiveSessionsReport): number {
  const keycloak = report.keycloak?.clients.reduce((sum, c) => sum + c.count, 0) ?? 0
  return report.channels.total > 0 ? report.channels.total : keycloak
}

/** The words the demo's screens use, one sentence each - to look up, not to read through. */
const GLOSSARY: { term: string; meaning: string }[] = [
  { term: t('Identifizieren'), meaning: t('Nachweisen, wer Sie sind: mit dem Freischaltcode aus einem Brief, dem Online-Ausweis oder über Nect.') },
  { term: t('Anmeldeverfahren'), meaning: t('Womit Sie sich beim nächsten Mal wieder anmelden, etwa SMS, Passwort, Geräteschlüssel oder KOBIL.') },
  {
    term: t('Sicherheitsniveau'),
    meaning: t('Wie sicher feststeht, dass Sie es sind, von 1 bis 3: SMS allein reicht für 1, zwei Verfahren verschiedener Art oder der Geräteschlüssel reichen für 2.'),
  },
  { term: t('Step-up'), meaning: t('Ein zusätzlicher Nachweis mitten in der Sitzung, wenn eine Aktion ein höheres Sicherheitsniveau verlangt.') },
  { term: t('Journey'), meaning: t('Ein Vorgang von Anfang bis Ende, etwa „Neues Konto anlegen“. Welcher Schritt als Nächstes kommt, entscheidet der Server.') },
  {
    term: t('Rolle'),
    meaning: t('Wen die Demo hinter einem Konto kennt: Versicherter (bei uns versichert), Partner (nur mit Partnernummer bekannt) oder Interessent (noch keiner Person zugeordnet).'),
  },
  { term: t('Personenverzeichnis'), meaning: t('Das simulierte fremde System mit allen Testpersonen. Es verschickt die Freischaltcodes per Brief.') },
  { term: t('Verknüpfung'), meaning: t('Die App merkt sich, zu welchem Konto dieses Gerät gehört, und schlägt deshalb das Anmelden vor. Ein Nachweis ist das nicht.') },
  { term: t('Geräteschlüssel'), meaning: t('Ein Anmeldeverfahren: ein Schlüssel nur für Ihr Konto, per PIN oder Biometrie freigegeben. Er allein reicht für Sicherheitsniveau 2.') },
  { term: t('AccessToken'), meaning: t('Der Ausweis, mit dem eine Anwendung Sie nach der Anmeldung erkennt. In der App ist er an dieses Gerät gebunden (DPoP).') },
]

const READ_ON: { text: () => string; href: string; label: () => string }[] = [
  { text: () => t('Ein gesprochenes Video spielt die Aufgaben dieser Seite einmal durch.'), href: `${REPO}/blob/main/docs/media/demo.mp4`, label: () => t('Demo-Video ansehen') },
  {
    text: () => t('Ein Erklärvideo von gut fünf Minuten zeigt Konzepte, Stand und die Vor- und Nachteile des Ansatzes.'),
    href: `${REPO}/blob/main/docs/media/erklaervideo.mp4`,
    label: () => t('Erklärvideo ansehen'),
  },
  { text: () => t('Die Dokumentation beginnt mit einem Überblick über Begriffe und Aufbau.'), href: `${REPO}/blob/main/docs/01-ueberblick.md`, label: () => t('Überblick lesen') },
  {
    text: () => t('Eine durchgehende Geschichte: Mara registriert sich, kommt wieder und erhöht ihr Sicherheitsniveau.'),
    href: `${REPO}/blob/main/docs/11-beispiel-story.md`,
    label: () => t('Beispiel lesen'),
  },
  { text: () => t('Der Quellcode der Demo auf GitHub.'), href: REPO, label: () => t('Quellcode öffnen') },
]

function Glossary() {
  return (
    <>
      <div className="start-intro">
        <h1>{t('Begriffe')}</h1>
        <p className="start-lead">{t('Die Wörter, die Ihnen in der Demo immer wieder begegnen – kurz erklärt.')}</p>
      </div>
      <dl className="start-glossary">
        {GLOSSARY.map((entry) => (
          <div key={entry.term}>
            <dt>{entry.term}</dt>
            <dd>{entry.meaning}</dd>
          </div>
        ))}
      </dl>
      <section className="start-section">
        <h2>{t('Weiterlesen')}</h2>
        <ul className="start-read">
          {READ_ON.map((item) => (
            <li key={item.href}>
              <span>{item.text()}</span>
              <a href={item.href} target="_blank" rel="noreferrer">
                {item.label()}
              </a>
            </li>
          ))}
        </ul>
      </section>
    </>
  )
}

/** Read-only: under which conditions this demo is running right now. Switched on /admin/. */
function ServerStatus() {
  const [info, setInfo] = useState<ServerInfo | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    fetchServerInfo()
      .then(setInfo)
      .catch((err) => setError(describeError(t('Server-Status laden fehlgeschlagen'), err)))
  }, [])

  if (error) return <div className="card error-card"><p>{error}</p></div>
  if (!info) return <p>{t('Lädt…')}</p>

  const up = info.operations ? info.operations.status === 'UP' : null
  return (
    <>
      <div className="start-intro start-intro-row">
        <div>
          <h1>{t('Server-Status')}</h1>
          <p className="start-lead">{t('So ist die Demo gerade eingestellt.')}</p>
        </div>
        {up !== null && (
          <span className={up ? 'start-health start-health--up' : 'start-health start-health--down'}>
            <span className="start-health-dot" aria-hidden="true" />
            {up ? t('Alles in Ordnung') : t('Gesamtzustand: {status}', { status: info.operations!.status })}
          </span>
        )}
      </div>

      <section className="start-section">
        <div className="start-section-head">
          <h2>{t('Einstellungen')}</h2>
          <a href="/admin/" target={ADMIN_TAB}>
            {t('In der Verwaltung ändern')}
          </a>
        </div>
        <ul className="start-settings">
          <li>
            <span className="start-setting-label">{t('Identitätsanbieter (Web-Kanal)')}</span>
            <strong>{info.keycloak ? t('Keycloak, Realm {realm}', { realm: info.keycloak.realm }) : t('Kein Keycloak - Web-Kanal nicht verfügbar')}</strong>
            {info.keycloak && (
              <a href={info.keycloak.baseUrl} target="_blank" rel="noreferrer">
                <code>{info.keycloak.baseUrl}</code>
              </a>
            )}
          </li>
          {info.keycloak && (
            <li>
              <span className="start-setting-label">{t('Erste Anmeldeseite')}</span>
              <strong>{info.keycloak.loa1Login === 'ORCHESTRATOR' ? t('Gleich alle Verfahren zur Wahl') : t('Erst das Passwort')}</strong>
            </li>
          )}
          <li>
            <span className="start-setting-label">{t('Registrierungsreihenfolge')}</span>
            <strong>{info.registrationEnrollFirst ? t('Enrollment zuerst') : t('Identifikation zuerst')}</strong>
          </li>
          <li>
            <span className="start-setting-label">{t('Demo-Werte in Antworten')}</span>
            <strong>{info.demoMode ? t('an (TANs, Testpersonen, Vorbelegung)') : t('aus')}</strong>
          </li>
          <li className="start-setting-wide">
            <span className="start-setting-label">{t('Gesperrte Verfahren')}</span>
            {info.disabledTools.length === 0 ? (
              <strong>{t('keine')}</strong>
            ) : (
              // One line per channel and reason - the reason once, not after every tool.
              disabledGroups(info.disabledTools).map((g) => (
                <span key={`${g.channel}|${g.reason}`} className="start-locked">
                  <span className={g.channel === 'APP' ? 'start-locked-channel start-locked-channel--app' : 'start-locked-channel start-locked-channel--web'}>
                    {g.channel === 'APP' ? t('App') : t('Web')}
                  </span>
                  {g.tools.map((tool) => (
                    <code key={tool}>{tool}</code>
                  ))}
                  {g.reason && <span className="start-locked-reason">{g.reason}</span>}
                </span>
              ))
            )}
          </li>
        </ul>
        <DeveloperTools demoMode={info.demoMode} />
      </section>

      {info.operations && <OperationsStatus operations={info.operations} />}
    </>
  )
}

/**
 * What the actuator reports (docs/07-betrieb.md Abschnitt 7). In operation it is read on the
 * management port; the demo shows it here too, read once when the tab opens.
 */
function OperationsStatus({ operations }: { operations: OperationsInfo }) {
  return (
    <section className="start-section">
      <h2>{t('Betrieb')}</h2>
      <p className="start-lead-small">
        <Tx
          text="Zustand und Kennzahlen, wie sie der Orchestrator für die Überwachung meldet (im Betrieb unter {path} auf einem eigenen Port)."
          path={<code>/actuator</code>}
        />
      </p>
      <ul className="start-health-list">
        {operations.components.map((c) => (
          <li key={c.name} className={c.status === 'UP' ? 'start-check start-check--up' : 'start-check start-check--down'}>
            <span className="start-check-mark" aria-hidden="true">
              {c.status === 'UP' ? '✓' : '!'}
            </span>
            <span>
              <strong>{healthComponentLabel(c.name)}</strong>
              <span>{c.status}</span>
            </span>
          </li>
        ))}
      </ul>
      {operations.metrics.length > 0 && (
        <ul className="start-metrics">
          {operations.metrics.map((m) => (
            <li key={`${m.name}|${JSON.stringify(m.tags)}`}>
              <span>{metricLabel(m.name, m.tags)}</span>
              <strong>{String(m.value)}</strong>
              {m.meanMillis != null && <span>{t('im Mittel {millis} ms', { millis: m.meanMillis.toFixed(0) })}</span>}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}

function healthComponentLabel(name: string): string {
  switch (name) {
    case 'db': return t('Datenbank')
    case 'keycloak': return t('Keycloak erreichbar')
    case 'livenessState': return t('Prozess lebt')
    case 'readinessState': return t('Nimmt Anfragen an')
    case 'diskSpace': return t('Speicherplatz')
    case 'ping': return t('Antwortet')
    default: return name
  }
}

function metricLabel(name: string, tags: Record<string, string>): string {
  switch (name) {
    case 'identity.ratelimit.blocked': return t('Mengenbegrenzung hat abgewiesen ({scope})', { scope: tags.scope ?? '' })
    case 'identity.retention.deleted': return t('Aufgeräumte Zeilen ({table})', { table: tags.table ?? '' })
    case 'identity.events.incomplete': return t('Offene Ereignisse')
    case 'http.client.requests': return t('Aufrufe an {host}', { host: tags['client.name'] ?? '' })
    default: return name
  }
}

function disabledGroups(tools: { tool: string; channel: string; reason?: string | null }[]) {
  const groups = new Map<string, { channel: string; reason: string; tools: string[] }>()
  for (const d of tools) {
    const key = `${d.channel}|${d.reason ?? ''}`
    const group = groups.get(key) ?? { channel: d.channel, reason: d.reason ?? '', tools: [] }
    group.tools.push(d.tool)
    groups.set(key, group)
  }
  return [...groups.values()]
}
