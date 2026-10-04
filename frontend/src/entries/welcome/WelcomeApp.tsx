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
import { Disclosure } from '../../components/Disclosure'
import { markAsStartWindow } from '../../startWindow'
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

/** The named tabs the demo's pages open in, so an open one is reused (docs/10-frontend.md #0). */
const APP_TAB = 'identity-demo-app-kanal'
const WEB_TAB = 'identity-demo-web-kanal'
const REGISTER_TAB = 'identity-demo-register'
const ADMIN_TAB = 'identity-demo-admin'
const MAILBOX_TAB = 'identity-demo-briefkasten'

const REPO = 'https://github.com/rpreissel/identity-demo'

/**
 * The landing page (docs/10-frontend.md #0): no channel logic, no DPoP key. It reads only the
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
    <div className="app welcome">
      <header className="app-header">
        <h1>{t('Identity Journey')}</h1>
        <p>{t('Legen Sie ein Konto an, melden Sie sich an wie bei einer Krankenkasse, und schauen Sie dabei hinter die Kulissen.')}</p>
      </header>

      <div className="app-tabs" role="tablist">
        {TABS.map((x) => (
          <button key={x.key} role="tab" aria-selected={tab === x.key} className={tab === x.key ? 'active' : ''} onClick={() => setTab(x.key)}>
            {x.label}
          </button>
        ))}
      </div>

      {tab === 'loslegen' && <GetStarted keycloak={keycloak} />}
      {tab === 'begriffe' && <Glossary />}
      {tab === 'status' && <ServerStatus />}
    </div>
  )
}

/** One task to try: what to do, and the button that opens the tab where it happens. */
function Task({ children, action }: { children: ReactNode; action: ReactNode }) {
  return (
    <li className="task-row">
      <span>{children}</span>
      {action}
    </li>
  )
}

function OpenLink({ href, target, label }: { href: string; target: string; label: string }) {
  return (
    <a className="button secondary" href={href} target={target}>
      {label}
    </a>
  )
}

/** Invites to play: one first action, then a few tasks, then what each tab plays. */
function GetStarted({ keycloak }: { keycloak: boolean | null }) {
  const webMissing = (
    <span className="task-note">
      <Tx text="Nur mit Keycloak ({befehl})" befehl={<code>./gradlew bootRunKc</code>} />
    </span>
  )
  return (
    <>
      <section className="card welcome-hero">
        <h2>{t('Fangen Sie in der App an')}</h2>
        <p>
          {t(
            'Registrieren Sie sich mit einer Testperson. Wählen Sie als Anmeldeverfahren „Gerät“: Dann ist die App an ' +
              'dieses Gerät gebunden, und Sie melden sich hier künftig ohne Passwort an. Das dauert etwa zwei Minuten, ' +
              'und alles, was Sie eingeben, steht schon im Formular oder in der Spalte daneben.',
          )}
        </p>
        <a className="button button-large" href="/app/?intent=register" target={APP_TAB}>
          {t('In der App registrieren')}
        </a>
      </section>

      <section className="card">
        <h2>{t('Danach ausprobieren')}</h2>
        <ol className="task-list">
          <Task action={<OpenLink href="/app/" target={APP_TAB} label={t('App öffnen')} />}>
            {t('In der App unter „Sicherheit“ die Anmeldung per QR-Code aktivieren und ein Passwort vergeben.')}
          </Task>
          <Task
            action={
              keycloak === false ? webMissing : <OpenLink href="/web/" target={WEB_TAB} label={t('Website öffnen')} />
            }
          >
            {t('Auf der Website mit dem Passwort anmelden und die Gesundheitsdaten öffnen: Dafür bestätigen Sie die Anmeldung in der App.')}
          </Task>
          <Task action={<OpenLink href="/personenverzeichnis/" target={REGISTER_TAB} label={t('Personenverzeichnis öffnen')} />}>
            {t('Im Personenverzeichnis Ihren Namen ändern und sehen, dass die App ihn übernimmt.')}
          </Task>
          <Task action={<OpenLink href="/personenverzeichnis/#einladungen" target={REGISTER_TAB} label={t('Einladung ausstellen')} />}>
            {t('Im Personenverzeichnis einen Brief mit Einmalkennwort verschicken und damit auf der Website einen Vorgang erledigen, ganz ohne Konto.')}
          </Task>
          <Task action={<OpenLink href="/admin/#journeytrace" target={ADMIN_TAB} label={t('Admin-Seite öffnen')} />}>
            {t('Auf der Admin-Seite verfolgen, welche Schritte Ihre Journey durchlaufen hat.')}
          </Task>
          <Task action={<OpenLink href="/app/" target={APP_TAB} label={t('App öffnen')} />}>
            {t('In der App unter „Sicherheit“ das Konto löschen und von vorn anfangen.')}
          </Task>
        </ol>
      </section>

      <section className="card">
        <h2>{t('Die Tabs der Demo')}</h2>
        <ul className="method-choice-list tab-tiles">
          <li>
            <a className="method-choice" href="/app/" target={APP_TAB} aria-label={t('Zum App-Kanal')}>
              <span className="method-choice-icon" aria-hidden="true">
                📱
              </span>
              <span className="method-choice-text">
                <span className="method-choice-label">{t('App')}</span>
                <span className="method-choice-hint">
                  {t('Registrieren, anmelden, Verfahren einrichten. Daneben erklärt die Demo jeden Schritt.')}
                </span>
              </span>
            </a>
          </li>
          <li>
            {keycloak === false ? (
              <div className="method-choice method-choice-disabled" aria-disabled="true">
                <span className="method-choice-icon" aria-hidden="true">
                  🌐
                </span>
                <span className="method-choice-text">
                  <span className="method-choice-label">{t('Website nicht verfügbar')}</span>
                  <span className="method-choice-hint">
                    <Tx
                      text="Nur mit echtem Keycloak - Server mit Profil {profil} starten ({befehl})"
                      profil={<code>keycloak</code>}
                      befehl={<code>./gradlew bootRunKc</code>}
                    />
                  </span>
                </span>
              </div>
            ) : (
              <a className="method-choice" href="/web/" target={WEB_TAB} aria-label={t('Zum Web-Kanal')}>
                <span className="method-choice-icon" aria-hidden="true">
                  🌐
                </span>
                <span className="method-choice-text">
                  <span className="method-choice-label">{t('Website')}</span>
                  <span className="method-choice-hint">
                    {t('Anmelden wie im Kundenportal, auch per QR-Code mit der App. Dahinter steht ein echtes Keycloak.')}
                  </span>
                </span>
              </a>
            )}
          </li>
          <li>
            <a className="method-choice" href="/personenverzeichnis/" target={REGISTER_TAB} aria-label={t('Zum Personenverzeichnis')}>
              <span className="method-choice-icon" aria-hidden="true">
                🏛️
              </span>
              <span className="method-choice-text">
                <span className="method-choice-label">{t('Personenverzeichnis')}</span>
                <span className="method-choice-hint">
                  {t('Die Testpersonen und ihre Freischaltcodes. Ein fremdes System, nur simuliert.')}
                </span>
              </span>
            </a>
          </li>
          <li>
            <a className="method-choice" href="/briefkasten/" target={MAILBOX_TAB} aria-label={t('Zum Briefkasten')}>
              <span className="method-choice-icon" aria-hidden="true">
                📬
              </span>
              <span className="method-choice-text">
                <span className="method-choice-label">{t('Briefkasten')}</span>
                <span className="method-choice-hint">
                  {t('Briefe, SMS und E-Mails an die Testpersonen, mit den Codes darin. Die Seite der Empfänger, nur simuliert.')}
                </span>
              </span>
            </a>
          </li>
          <li>
            <a className="method-choice" href="/admin/" target={ADMIN_TAB} aria-label={t('Zur Admin-Seite')}>
              <span className="method-choice-icon" aria-hidden="true">
                🛠️
              </span>
              <span className="method-choice-text">
                <span className="method-choice-label">{t('Admin')}</span>
                <span className="method-choice-hint">
                  {t('Jeden Schritt jeder Journey verfolgen, Verfahren sperren, die Demo zurücksetzen.')}
                </span>
              </span>
            </a>
          </li>
        </ul>
      </section>

      <section className="card">
        <Disclosure summary={t('Was ist echt, was simuliert?')}>
          <div className="real-vs-sim">
            <div>
              <h3>{t('Echt')}</h3>
              <ul>
                <li>{t('Der Orchestrator: Journeys, Konten, Verfahren, Tokens')}</li>
                <li>{t('Die DPoP-Signaturen - der Geräteschlüssel entsteht per WebCrypto im Browser')}</li>
                <li>
                  <Tx text="Keycloak und der Website-Login (OIDC mit PKCE) - nur mit Profil {profil}" profil={<code>keycloak</code>} />
                </li>
                <li>{t('Die Sicherheitsniveaus (loa1 bis loa3) und der Step-up')}</li>
              </ul>
            </div>
            <div>
              <h3>{t('Simuliert')}</h3>
              <ul>
                <li>{t('Das Smartphone - ein Browser-Tab')}</li>
                <li>{t('SMS- und E-Mail-Versand - die Codes stehen im Briefkasten')}</li>
                <li>{t('Der Brief mit dem Freischaltcode - ebenfalls im Briefkasten')}</li>
                <li>{t('Das Auslesen der eID-Karte und die Dienstleister Nect und KOBIL')}</li>
                <li>{t('Das Personenverzeichnis selbst')}</li>
              </ul>
            </div>
          </div>
        </Disclosure>
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
    <section className="card">
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

function Glossary() {
  return (
    <>
      <section className="card">
        <h2>{t('Begriffe')}</h2>
        <dl className="glossary">
          {GLOSSARY.map((entry) => (
            <div key={entry.term}>
              <dt>{entry.term}</dt>
              <dd>{entry.meaning}</dd>
            </div>
          ))}
        </dl>
      </section>
      <section className="card">
        <h2>{t('Weiterlesen')}</h2>
        <ul className="task-list">
          <li className="task-row">
            <span>{t('Ein gesprochenes Video spielt die Aufgaben dieser Seite einmal durch.')}</span>
            <a className="button secondary" href={`${REPO}/blob/main/docs/media/demo.mp4`} target="_blank" rel="noreferrer">
              {t('Demo-Video ansehen')}
            </a>
          </li>
          <li className="task-row">
            <span>{t('Ein Erklärvideo von gut fünf Minuten zeigt Konzepte, Stand und die Vor- und Nachteile des Ansatzes.')}</span>
            <a className="button secondary" href={`${REPO}/blob/main/docs/media/erklaervideo.mp4`} target="_blank" rel="noreferrer">
              {t('Erklärvideo ansehen')}
            </a>
          </li>
          <li className="task-row">
            <span>{t('Die Dokumentation beginnt mit einem Überblick über Begriffe und Aufbau.')}</span>
            <a className="button secondary" href={`${REPO}/blob/main/docs/01-ueberblick.md`} target="_blank" rel="noreferrer">
              {t('Überblick lesen')}
            </a>
          </li>
          <li className="task-row">
            <span>{t('Eine durchgehende Geschichte: Mara registriert sich, kommt wieder und erhöht ihr Sicherheitsniveau.')}</span>
            <a className="button secondary" href={`${REPO}/blob/main/docs/11-beispiel-story.md`} target="_blank" rel="noreferrer">
              {t('Beispiel lesen')}
            </a>
          </li>
          <li className="task-row">
            <span>{t('Der Quellcode der Demo auf GitHub.')}</span>
            <a className="button secondary" href={REPO} target="_blank" rel="noreferrer">
              {t('Quellcode öffnen')}
            </a>
          </li>
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
  if (!info) return <div className="card"><p>{t('Lädt…')}</p></div>

  return (
    <div className="card">
      <h2>{t('Server-Status')}</h2>
      <p>
        {t('So ist die Demo gerade eingestellt.')}{' '}
        <Tx
          text="Reihenfolge der Registrierung und gesperrte Verfahren ändern Sie auf der {link}; Keycloak und Demo-Werte legt der Serverstart fest."
          link={<a href="/admin/">{t('Admin-Seite')}</a>}
        />
      </p>
      <ul className="status-list">
        <li>
          <span className="label">{t('Identitätsanbieter (Web-Kanal)')}</span>
          <span className="value">
            {info.keycloak ? t('Keycloak, Realm {realm}', { realm: info.keycloak.realm }) : t('Kein Keycloak - Web-Kanal nicht verfügbar')}
          </span>
        </li>
        {info.keycloak && (
          <li>
            <span className="label">{t('Keycloak')}</span>
            <a className="value" href={info.keycloak.baseUrl} target="_blank" rel="noreferrer">
              {info.keycloak.baseUrl}
            </a>
          </li>
        )}
        {info.keycloak && (
          <li>
            <span className="label">{t('Anmeldeseiten')}</span>
            <span className="value">{info.keycloak.loginTheme === 'KEYCLOAKIFY' ? 'Keycloakify (React)' : 'FreeMarker'}</span>
          </li>
        )}
        {info.keycloak && (
          <li>
            <span className="label">{t('Erste Anmeldeseite')}</span>
            <span className="value">{info.keycloak.loa1Login === 'ORCHESTRATOR' ? t('Gleich alle Verfahren zur Wahl') : t('Erst das Passwort')}</span>
          </li>
        )}
        <li>
          <span className="label">{t('Registrierungsreihenfolge')}</span>
          <span className="value">{info.registrationEnrollFirst ? t('Enrollment zuerst') : t('Identifikation zuerst')}</span>
        </li>
        <li className={info.disabledTools.length === 0 ? undefined : 'status-stacked'}>
          <span className="label">{t('Gesperrte Verfahren')}</span>
          {info.disabledTools.length === 0 ? (
            <span className="value">{t('keine')}</span>
          ) : (
            // One line per channel and reason - the reason once, not after every tool.
            <ul className="status-sublist">
              {disabledGroups(info.disabledTools).map((g) => (
                <li key={`${g.channel}|${g.reason}`}>
                  <span className="status-group">
                    {g.channel === 'APP' ? t('App') : t('Web')}
                    {g.reason ? ` · ${g.reason}` : ''}
                  </span>
                  <span className="value">{g.tools.join(', ')}</span>
                </li>
              ))}
            </ul>
          )}
        </li>
        <li>
          <span className="label">{t('Demo-Werte in Antworten')}</span>
          <span className="value">{info.demoMode ? t('an (TANs, Testpersonen, Vorbelegung)') : t('aus')}</span>
        </li>
      </ul>
      {info.operations && <OperationsStatus operations={info.operations} />}
    </div>
  )
}

/**
 * What the actuator reports (docs/07-betrieb.md Abschnitt 7). In operation it is read on the
 * management port; the demo shows it here too, read once when the tab opens.
 */
function OperationsStatus({ operations }: { operations: OperationsInfo }) {
  return (
    <>
      <h3>{t('Betrieb')}</h3>
      <p>
        <Tx
          text="Zustand und Kennzahlen, wie sie der Orchestrator für die Überwachung meldet (im Betrieb unter {path} auf einem eigenen Port)."
          path={<code>/actuator</code>}
        />
      </p>
      <ul className="status-list">
        <li>
          <span className="label">{t('Gesamtzustand')}</span>
          <span className="value">{operations.status}</span>
        </li>
        {operations.components.map((c) => (
          <li key={c.name}>
            <span className="label">{healthComponentLabel(c.name)}</span>
            <span className="value">{c.status}</span>
          </li>
        ))}
        {operations.metrics.map((m) => (
          <li key={`${m.name}|${JSON.stringify(m.tags)}`}>
            <span className="label">{metricLabel(m.name, m.tags)}</span>
            <span className="value">
              {m.meanMillis != null
                ? t('{count} Aufrufe, im Mittel {millis} ms', { count: String(m.value), millis: m.meanMillis.toFixed(0) })
                : String(m.value)}
            </span>
          </li>
        ))}
      </ul>
    </>
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
