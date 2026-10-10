import { t } from '../texts'
import { ErrorCard } from './ErrorCard'
import { errorMessage } from '../errorMessage'
import { Tx } from '../Tx'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { type KeycloakInfo } from '../api'
import { createWebOidc, LoginNotCompletedError, SessionEndedError, type TokenSet } from '../webOidc'
import { parseJwtPayload } from '../jwt'
import { personenverzeichnisApi, type Vorgang } from '../personenverzeichnisApi'
import { shorten } from '../format'
import { UnavailableTools } from './UnavailableTools'
import { Disclosure } from './Disclosure'
import { isBelowAcr } from '../acr'
import { accountRole } from '../accountRole'
import { BrowserFrame } from './BrowserFrame'
import { LanguageSwitch } from './LanguageSwitch'
import { Demo, DemoArea, DemoProvider } from './DemoArea'
import { StepExplanation } from './StepExplanation'
import { SessionSummary } from './SessionSummary'
import { ButtonDiagrams } from './ButtonDiagrams'
import '../phone.css'
import '../browser.css'

const SESSION_KEY = 'web-kanal-tokens'

function loadStoredTokens(): TokenSet | null {
  const raw = sessionStorage.getItem(SESSION_KEY)
  if (!raw) return null
  try {
    return JSON.parse(raw) as TokenSet
  } catch {
    return null
  }
}

function storeTokens(tokens: TokenSet | null) {
  if (tokens) sessionStorage.setItem(SESSION_KEY, JSON.stringify(tokens))
  else sessionStorage.removeItem(SESSION_KEY)
}

function formatRemaining(expiresAt: number): string {
  const seconds = Math.round((expiresAt - Date.now()) / 1000)
  if (seconds <= 0) return t('abgelaufen')
  if (seconds < 120) return t('{sekunden}s', { sekunden: seconds })
  return t('{minuten}min', { minuten: Math.round(seconds / 60) })
}

/** The portal page to show again after a round trip to Keycloak (a step-up started from it). */
const VIEW_KEY = 'identity-demo-web-view'

type PortalView = 'home' | 'profile' | 'security' | 'protected' | 'process'

function readStored<T extends string>(storage: () => Storage, key: string, allowed: readonly T[]): T | undefined {
  try {
    const value = storage().getItem(key)
    return value && (allowed as readonly string[]).includes(value) ? (value as T) : undefined
  } catch {
    return undefined
  }
}

function writeStored(storage: () => Storage, key: string, value: string | null) {
  try {
    if (value === null) storage().removeItem(key)
    else storage().setItem(key, value)
  } catch {
    // A convenience only - without storage the default applies again.
  }
}

/**
 * The Web channel as a website in a browser window next to the demo column, like the App channel's
 * phone. A plain customer portal with a normal sign-in and a protected area (loa1 / loa2), both a
 * real browser login against Keycloak (webOidc.ts), whose every step is an orchestrator tool
 * (ADR-58). The orchestrator only hears from Keycloak's server side, never from here.
 */
export function WebChannelView({ keycloak }: { keycloak: KeycloakInfo }) {
  const {
    completeLoginIfRedirected,
    redirectToLogin,
    redirectToLogout,
    redirectToManageMethods,
    redirectToStepUp,
    refreshTokens,
  } = useMemo(() => createWebOidc(keycloak), [keycloak])
  const [tokens, setTokens] = useState<TokenSet | null>(() => loadStoredTokens())
  const [error, setError] = useState('')
  // "Anmeldung abgebrochen" is the user's own choice, not a failure - shown as a note, not an error.
  const [notice, setNotice] = useState('')
  const [view, setView] = useState<PortalView>(
    () => readStored(() => sessionStorage, VIEW_KEY, ['home', 'profile', 'security', 'protected', 'process']) ?? 'home',
  )
  const [vorgaenge, setVorgaenge] = useState<Vorgang[]>([])
  const completingRef = useRef(false)

  // The process names for the demo process page; the register invites to them.
  useEffect(() => {
    personenverzeichnisApi.vorgaenge().then(setVorgaenge).catch(() => setVorgaenge([]))
  }, [])

  // Picks up `?code=...` after the redirect back. A ref guards against StrictMode's double
  // invocation: a second exchange would try to redeem the already-used code and fail.
  useEffect(() => {
    if (completingRef.current) return
    completingRef.current = true
    completeLoginIfRedirected()
      .then((fresh) => {
        if (fresh) {
          setTokens(fresh)
          storeTokens(fresh)
        }
      })
      .catch((err) => {
        if (err instanceof LoginNotCompletedError && err.cancelled) setNotice(err.message)
        else setError(errorMessage(err))
      })
      .finally(() => {
        completingRef.current = false
        writeStored(() => sessionStorage, VIEW_KEY, null)
      })
  }, [completeLoginIfRedirected])

  function clearMessages() {
    setError('')
    setNotice('')
  }

  /** Sign in at the given level; `thenShow` is the page to land on once Keycloak sends the user back. */
  function login(acrValue: '1' | '2', thenShow: PortalView = 'home') {
    clearMessages()
    writeStored(() => sessionStorage, VIEW_KEY, thenShow)
    redirectToLogin(acrValue).catch((err) => setError(errorMessage(err)))
  }

  function refresh() {
    if (!tokens?.refreshToken) return
    clearMessages()
    refreshTokens(tokens.refreshToken)
      .then((fresh) => {
        setTokens(fresh)
        storeTokens(fresh)
      })
      .catch((err) => {
        if (err instanceof SessionEndedError) {
          setTokens(null)
          storeTokens(null)
          setView('home')
          setNotice(err.message)
        } else {
          setError(errorMessage(err))
        }
      })
  }

  function stepUp(thenShow: PortalView) {
    clearMessages()
    writeStored(() => sessionStorage, VIEW_KEY, thenShow)
    redirectToStepUp().catch((err) => setError(errorMessage(err)))
  }

  function manageMethods() {
    clearMessages()
    writeStored(() => sessionStorage, VIEW_KEY, 'security')
    redirectToManageMethods().catch((err) => setError(errorMessage(err)))
  }

  /**
   * exp is in whole seconds since epoch. Anything undecodable counts as expired: at worst a refresh
   * that might have worked is skipped, never a wrong logout.
   */
  function isExpired(token: string | undefined): boolean {
    const exp = token ? parseJwtPayload(token)?.exp : undefined
    return typeof exp !== 'number' || exp * 1000 <= Date.now()
  }

  /**
   * Keycloak's end_session_endpoint rejects an expired id_token_hint with an error page instead of
   * a logout. A valid idToken needs no refresh, an expired refresh token makes one pointless; only
   * an expired idToken with a live refresh token is worth the round trip. Logging out without a hint
   * (client_id + post_logout_redirect_uri) beats handing Keycloak a token it will reject.
   */
  async function logout() {
    let idToken = tokens?.idToken
    if (isExpired(idToken)) {
      idToken = undefined
      if (tokens?.refreshToken && !isExpired(tokens.refreshToken)) {
        try {
          idToken = (await refreshTokens(tokens.refreshToken)).idToken
        } catch {
          idToken = undefined
        }
      }
    }
    setTokens(null)
    storeTokens(null)
    setView('home')
    redirectToLogout(idToken)
  }

  const accessClaims = tokens ? parseJwtPayload(tokens.accessToken) : null
  const idClaims = tokens?.idToken ? parseJwtPayload(tokens.idToken) : null
  const currentAcr = typeof accessClaims?.acr === 'string' ? accessClaims.acr : undefined
  const refreshExp = tokens?.refreshToken ? parseJwtPayload(tokens.refreshToken)?.exp : undefined
  const refreshExpiresAt = typeof refreshExp === 'number' ? refreshExp * 1000 : undefined
  const belowLoa2 = isBelowAcr(currentAcr, 'loa2')
  // "name" is the standard OIDC claim from the "profile" scope (Keycloak's full-name mapper over
  // firstName/lastName, read through the user federation, ADR-38); the role as in the app (ADR-34),
  // from person_id/versnr, which the federation provides as Keycloak attributes.
  const personName = typeof idClaims?.name === 'string' ? idClaims.name : undefined
  const role = idClaims ? accountRole(idClaims.person_id, idClaims.versnr) : undefined
  const claimText = (value: unknown) => (typeof value === 'string' && value !== '' ? value : undefined)
  // A token from a one-time password carries the process it is good for, and only then
  // (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). The page then shows that process and nothing else.
  const process = claimText(accessClaims?.process)
  const invitation = claimText(accessClaims?.invitation)
  const processName = (id: string) => vorgaenge.find((v) => v.id === id)?.name ?? id

  /**
   * Stands in for the business system: it reports the process done to the register, with the
   * invitation's id from the token. The register ends the invitation, and Keycloak refuses the next
   * token - the renewal right after shows it.
   */
  async function completeProcess() {
    if (!invitation) return
    clearMessages()
    try {
      await personenverzeichnisApi.einladungAbschliessen(invitation)
      setNotice(t('Der Vorgang ist abgeschlossen. Das Einmalkennwort gilt nicht mehr.'))
      // The logout reaches Keycloak shortly after, as an event of the register. Until then a
      // renewal may still succeed, so the page asks a few times, a second apart.
      let refreshToken = tokens?.refreshToken
      for (let attempt = 0; refreshToken && attempt < 5; attempt++) {
        await new Promise((resolve) => setTimeout(resolve, 1000))
        try {
          const fresh = await refreshTokens(refreshToken)
          setTokens(fresh)
          storeTokens(fresh)
          refreshToken = fresh.refreshToken
        } catch (err) {
          if (!(err instanceof SessionEndedError)) throw err
          setTokens(null)
          storeTokens(null)
          setView('home')
          setNotice(t('Der Vorgang ist abgeschlossen, Keycloak hat die Sitzung beendet.'))
          return
        }
      }
    } catch (err) {
      setError(errorMessage(err))
    }
  }

  const signedOut = (
    <>
      <section className="portal-hero">
        <h1>{t('Willkommen im Kundenportal')}</h1>
        <p>{t('Hier erledigen Sie Ihre Anliegen online - rund um die Uhr.')}</p>
      </section>
      <div className="portal-tiles">
        <div className="portal-tile">
          <h2>{t('Meine Daten')}</h2>
          <p>{t('Kontaktdaten und Versicherungsstatus ansehen.')}</p>
          <button onClick={() => login('1', 'profile')}>{t('Meine Daten ansehen')}</button>
        </div>
        <div className="portal-tile portal-tile--protected">
          <h2>
            <span aria-hidden="true">🔒 </span>
            {t('Gesundheitsdaten')}
          </h2>
          <p>{t('Befunde und Rechnungen. Dafür brauchen Sie eine besonders gesicherte Anmeldung.')}</p>
          <button onClick={() => login('2', 'protected')}>{t('Sicher anmelden')}</button>
        </div>
        <div className="portal-tile">
          <h2>
            <span aria-hidden="true">📮 </span>
            {t('Vorgang mit Einmalkennwort')}
          </h2>
          <p>{t('Sie haben einen Brief mit einem Einmalkennwort bekommen? Damit erledigen Sie diesen einen Vorgang, auch ohne Konto.')}</p>
          <button onClick={() => login('1', 'process')}>{t('Mit Einmalkennwort anmelden')}</button>
        </div>
      </div>
      <p className="portal-note">
        {t('Die Anmeldung übernimmt unser Anmeldedienst Keycloak. Sie werden dafür auf seine Seiten weitergeleitet und danach hierher zurückgebracht.')}
      </p>
      <ButtonDiagrams
        entries={[
          { label: t('Anmelden'), diagram: 'webLoginLoa1' },
          { label: t('Sicher anmelden'), diagram: 'webLoginLoa2' },
          { label: t('Mit Einmalkennwort anmelden'), diagram: 'webLoginInvite' },
        ]}
      />
    </>
  )

  const back = (
    <button className="portal-back" onClick={() => setView('home')}>
      {t('Zurück zur Übersicht')}
    </button>
  )

  const row = (label: string, value?: string) =>
    value ? (
      <li>
        <span className="label">{label}</span>
        <span className="value">{value}</span>
      </li>
    ) : null

  const signedIn: Record<PortalView, ReactNode> = {
    home: (
      <>
        <section className="portal-hero">
          <h1>{personName ? t('Willkommen, {name}!', { name: personName }) : t('Willkommen!')}</h1>
          <p>{role ? t('Sie sind als {status} angemeldet.', { status: role }) : t('Sie sind angemeldet.')}</p>
        </section>
        <ul className="portal-menu">
          <li>
            <button onClick={() => setView('profile')}>
              <strong>{t('Profil')}</strong>
              <span>{t('Ihre persönlichen Daten')}</span>
            </button>
          </li>
          <li>
            <button onClick={() => setView('security')}>
              <strong>{t('Sicherheit')}</strong>
              <span>{t('Sicherheitsniveau und Anmeldeverfahren')}</span>
            </button>
          </li>
          <li>
            <button onClick={() => setView('protected')}>
              <strong>
                <span aria-hidden="true">🔒 </span>
                {t('Gesundheitsdaten')}
              </strong>
              <span>{belowLoa2 ? t('Verlangt eine besonders gesicherte Anmeldung') : t('Befunde und Rechnungen')}</span>
            </button>
          </li>
          <li>
            <button onClick={() => setView('process')}>
              <strong>
                <span aria-hidden="true">📮 </span>
                {t('Vorgang')}
              </strong>
              <span>{t('Ein Vorgang, zu dem wir per Brief eingeladen haben')}</span>
            </button>
          </li>
        </ul>
      </>
    ),
    profile: (
      <>
        {back}
        <h1>{t('Profil')}</h1>
        <ul className="status-list portal-list">
          {row(t('Vor- und Nachname'), personName)}
          {row(t('Status'), role)}
          {row(t('Mitgliedsnummer'), claimText(idClaims?.versnr))}
          {row(t('Partnernummer'), claimText(idClaims?.person_id))}
          {row(t('E-Mail'), claimText(idClaims?.email))}
        </ul>
      </>
    ),
    security: (
      <>
        {back}
        <h1>{t('Sicherheit')}</h1>
        <ul className="status-list portal-list">{row(t('Sicherheitsniveau'), currentAcr ?? '–')}</ul>
        {belowLoa2 && (
          <section className="portal-section">
            <h2>{t('Sicherheitsniveau erhöhen')}</h2>
            <p>{t('Bestätigen Sie Ihre Anmeldung mit einem zweiten Verfahren, dann erreichen Sie Sicherheitsniveau 2 - ohne sich neu anzumelden.')}</p>
            <button onClick={() => stepUp('security')}>{t('Sicherheitsniveau 2 anfordern')}</button>
          </section>
        )}
        <section className="portal-section">
          <h2>{t('Anmeldeverfahren verwalten')}</h2>
          <p>{t('Verfahren hinzufügen oder entfernen. Das geschieht auf den Seiten des Anmeldedienstes.')}</p>
          <button className="secondary" onClick={manageMethods}>
            {t('Anmeldeverfahren verwalten')}
          </button>
        </section>
        <ButtonDiagrams
          entries={[
            ...(belowLoa2 ? [{ label: t('Sicherheitsniveau 2 anfordern'), diagram: 'stepUp' as const }] : []),
            { label: t('Anmeldeverfahren verwalten'), diagram: 'manageMethods' },
          ]}
        />
      </>
    ),
    process: process ? (
      <>
        <h1>{t('Vorgang: {vorgang}', { vorgang: processName(process) })}</h1>
        <p>{t('Sie sind mit einem Einmalkennwort angemeldet. Diese Anmeldung gilt nur für diesen Vorgang.')}</p>
        <ul className="status-list portal-list">
          {row(t('Vor- und Nachname'), personName)}
          {row(t('Mitgliedsnummer'), claimText(idClaims?.versnr))}
          {row(t('Partnernummer'), claimText(idClaims?.person_id))}
          {row(t('Sicherheitsniveau'), currentAcr)}
        </ul>
        <section className="portal-section">
          <h2>{t('Vorgang beenden')}</h2>
          <p>{t('Ist der Vorgang erledigt, meldet das Fachsystem ihn beim Personenverzeichnis ab. Danach gilt das Einmalkennwort nicht mehr, und Keycloak stellt keine Tokens mehr aus.')}</p>
          <div className="form-actions">
            <button onClick={completeProcess}>{t('Vorgang beenden')}</button>
            <button className="secondary" onClick={logout}>{t('Abmelden')}</button>
          </div>
        </section>
      </>
    ) : (
      <>
        {back}
        <h1>{t('Vorgang')}</h1>
        <p>{t('Sie sind mit Ihrem Konto angemeldet. Ihr Token trägt keinen Vorgang, es gilt für jeden Vorgang ohne Einschränkung.')}</p>
        <p>{t('Um sich mit einem Einmalkennwort anzumelden, melden Sie sich zuerst ab: Eine Keycloak-Sitzung gehört genau einem Nutzer.')}</p>
        <button className="secondary" onClick={logout}>{t('Abmelden')}</button>
      </>
    ),
    protected: (
      <>
        {back}
        <h1>
          <span aria-hidden="true">🔒 </span>
          {t('Gesundheitsdaten')}
        </h1>
        {belowLoa2 ? (
          <section className="portal-section">
            <p>{t('Dieser Bereich verlangt eine besonders gesicherte Anmeldung (Sicherheitsniveau 2). Bestätigen Sie Ihre Anmeldung mit einem zweiten Verfahren.')}</p>
            <button onClick={() => stepUp('protected')}>{t('Sicher anmelden')}</button>
            <ButtonDiagrams entries={[{ label: t('Sicher anmelden'), diagram: 'stepUp' }]} />
          </section>
        ) : (
          <ul className="status-list portal-list">
            {row(t('Letzter Befund'), t('Blutbild, 12.08. (Beispiel)'))}
            {row(t('Offene Rechnungen'), t('keine'))}
          </ul>
        )}
      </>
    ),
  }

  // The demo column's "why / what / who" for the page the website shows right now.
  const explanation = tokens
    ? {
        idleReason: t('Keycloak hat die Website angemeldet und ihr Tokens ausgestellt.'),
        does: t('Die Website zeigt, was im ID-Token steht, und hält das Zugangstoken für Anfragen bereit. Den Orchestrator fragt nur Keycloak, im Hintergrund - die Website spricht nie mit ihm.'),
        actor: t('Sie. Sicherheitsniveau erhöhen, Anmeldeverfahren verwalten und Abmelden führen jeweils wieder über Keycloak.'),
        technical: `Client ${keycloak.browserClientId} · acr ${currentAcr ?? '-'}`,
      }
    : {
        idleReason: t('Auf der Website ist noch niemand angemeldet.'),
        does: t('„Anmelden“ fordert bei Keycloak Sicherheitsniveau 1 an, „Sicher anmelden“ Sicherheitsniveau 2. Welche Verfahren Keycloak dann anbietet, entscheidet im Hintergrund der Orchestrator.'),
        actor: t('Sie. Die Website hat noch kein Token.'),
        technical: `Client ${keycloak.browserClientId}`,
      }

  return (
    <DemoProvider>
      {(demoTargets) => (
        <div className="app-stage app-stage--web">
          <div className="app-stage__browser">
            <BrowserFrame url="kundenportal.example">
              <div className="portal">
                <header className="portal-header">
                  <span className="portal-brand">{t('Kundenportal')}</span>
                  <span className="portal-header__actions">
                  <LanguageSwitch className="portal-languages" buttonClassName="portal-language" />
                  {tokens ? (
                    <button className="secondary small" onClick={logout}>
                      {t('Abmelden')}
                    </button>
                  ) : (
                    <button className="small" onClick={() => login('1')}>
                      {t('Anmelden')}
                    </button>
                  )}
                  </span>
                </header>
                <main className="portal-main">
                  <Demo>
                    <StepExplanation {...explanation} />
                  </Demo>
                  {notice && <div className="hint">{notice}</div>}
                  {error && <ErrorCard>{error}</ErrorCard>}
                  {tokens ? signedIn[process ? 'process' : view] : signedOut}
                </main>
              </div>
            </BrowserFrame>
          </div>

          <DemoArea
            head={{ title: t('Web-Kanal'), tag: t('Echt · Keycloak') }}
            targets={demoTargets}
            session={
              <SessionSummary
                signedIn={!!tokens}
                name={personName}
                role={role}
                acr={currentAcr}
                amr={Array.isArray(accessClaims?.amr) ? accessClaims.amr.map(String) : undefined}
              />
            }
            intro={{
              id: 'web',
              title: t('Dieser Tab ist eine Website'),
              body: (
                <>
                  <p>
                    <Tx
                      text={
                        'Stellen Sie sich das Kundenportal Ihrer Versicherung im Browser vor. „Anmelden“ leitet Sie - wie bei ' +
                        'jeder großen Website - zu einem {keycloak} weiter (OpenID Connect mit PKCE), und ' +
                        'das AccessToken kommt direkt von dort zurück.'
                      }
                      keycloak={<strong>{t('echten Keycloak')}</strong>}
                    />
                  </p>
                  <p>
                    {t(
                      'Was Keycloak auf seinen Anmeldeseiten abfragt, entscheidet im Hintergrund derselbe Orchestrator wie in der ' +
                        'App: gleiche Konten, gleiche Verfahren, gleiche Sicherheitsniveaus. Ist die App schon angemeldet, können Sie ' +
                        'den Login dort per QR-Code bestätigen. Simuliert ist nur die Website selbst - sie ist diese Seite.',
                    )}
                  </p>
                </>
              ),
            }}
          >
            {!tokens && (
              <div className="card web-unavailable">
                <UnavailableTools channel="WEB" />
              </div>
            )}

            {tokens && (
              <div className="card">
                <h3 className="section-heading">{t('Zugangstoken')}</h3>
                <ul className="status-list">
                  <li>
                    <span className="label">{t('Gültig noch')}</span>
                    <span className="value value-plain">{formatRemaining(tokens.expiresAt)}</span>
                  </li>
                  {/* Keycloak's refresh token is a JWT too - its own exp says how long the website
                      can still renew without a new sign-in (same line as the App's TokenPanel). */}
                  {refreshExpiresAt !== undefined && (
                    <li>
                      <span className="label">{t('RefreshToken gültig noch')}</span>
                      <span className="value value-plain">{formatRemaining(refreshExpiresAt)}</span>
                    </li>
                  )}
                </ul>
                <div className="form-actions">
                  <button className="secondary" onClick={refresh} disabled={!tokens.refreshToken}>
                    {t('AccessToken aktualisieren')}
                  </button>
                </div>

                <Disclosure summary={t('Technische Details (Token, Claims)')}>
                  <ul className="status-list">
                    <li>
                      <span className="label">AccessToken</span>
                      <span className="value" title={tokens.accessToken}>
                        {shorten(tokens.accessToken, 12, 8)}
                      </span>
                    </li>
                  </ul>
                  {process && (
                    <>
                      <h4>{t('Vorgangs-Marker')}</h4>
                      <ul className="status-list">
                        <li><span className="label">process</span><span className="value">{process}</span></li>
                        <li><span className="label">invitation</span><span className="value" title={invitation}>{invitation && shorten(invitation, 12, 8)}</span></li>
                        <li><span className="label">sub</span><span className="value">{String(accessClaims?.sub ?? '')}</span></li>
                        <li><span className="label">orchestrator_account_id</span><span className="value">{t('fehlt - kein Konto')}</span></li>
                      </ul>
                    </>
                  )}
                  {accessClaims && <ClaimList title="AccessToken-Claims" claims={accessClaims} />}
                  {idClaims && <ClaimList title="IdToken-Claims" claims={idClaims} />}
                </Disclosure>
              </div>
            )}
          </DemoArea>
        </div>
      )}
    </DemoProvider>
  )
}

function ClaimList({ title, claims }: { title: string; claims: Record<string, unknown> }) {
  return (
    <>
      <h4>{title}</h4>
      <ul className="status-list">
        {Object.entries(claims).map(([key, value]) => (
          <li key={key}>
            <span className="label">{key}</span>
            <span className="value">{Array.isArray(value) ? value.join(', ') : typeof value === 'object' ? JSON.stringify(value) : String(value)}</span>
          </li>
        ))}
      </ul>
    </>
  )
}
