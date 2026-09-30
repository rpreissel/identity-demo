import { t } from '../texts'
import { useEffect, useState } from 'react'
import type { DpopKeyPair } from '../dpop.ts'
import type { ActiveMethodView, DemoInfo, IdTokenClaims } from '../types'
import { getIdClaims } from '../api.ts'
import { DiagramTrigger } from './DiagramHint'
import { Demo } from './DemoArea'
import { Disclosure } from './Disclosure'
import { StepActions, StepNav } from './PhoneFrame'
import { JOURNEY_DIAGRAMS } from '../journeyDiagrams'
import { TokenPanel } from './TokenPanel'
import { isBelowAcr } from '../acr'
import { accountRole } from '../accountRole'
import { enrollmentToolFor, metaFor } from '../tools/registry'

/** Display name for a method with no user-chosen label (singleton methods - email/sms/password). */
const DEFAULT_METHOD_LABELS: Record<string, string> = {
  sms: t('SMS'),
  email: t('E-Mail'),
  password: t('Passwort'),
  device: t('Gerät'),
  qr: t('QR-Login'),
  kobil: t('KOBIL'),
}

function kindLabel(method: ActiveMethodView): string {
  return DEFAULT_METHOD_LABELS[method.method] ?? method.method
}

function labelFor(method: ActiveMethodView): string {
  return method.label ?? kindLabel(method)
}

/** Same German factor-type names as the backend's DefaultAuthPolicy.germanFactorType. */
const FACTOR_TYPE_LABELS: Record<string, string> = {
  POSSESSION: t('Besitz'),
  KNOWLEDGE: t('Wissen'),
  INHERENCE: t('Inhärenz'),
}

function factorTypesLabel(method: ActiveMethodView): string | undefined {
  if (!method.factorTypes || method.factorTypes.length === 0) return undefined
  return method.factorTypes.map((type) => FACTOR_TYPE_LABELS[type] ?? type).join(' + ')
}

/**
 * The ADR-5 three-way cap (docs/12-entscheidungen.md), made visible instead of surfacing later as
 * "Sicherheitsniveau nicht erreichbar". effectiveAcr is what this method contributes today. It can
 * be lower than its maxAcr if it was enrolled while the session had proven less (enrolledUnderAcr).
 */
function acrDetailLabel(method: ActiveMethodView): string | undefined {
  if (!method.maxAcr) return undefined
  const capped = method.effectiveAcr && method.effectiveAcr !== method.maxAcr
  return capped
    ? t('{wirksam} (gedeckelt - max. {max}, eingerichtet unter {eingerichtet})', {
        wirksam: String(method.effectiveAcr),
        max: method.maxAcr,
        eingerichtet: String(method.enrolledUnderAcr),
      })
    : t('{wirksam} (max. {max})', { wirksam: method.effectiveAcr ?? method.maxAcr, max: method.maxAcr })
}

/**
 * The diagram of a section's journey, in the demo column next to the phone (DemoArea) - a real app
 * shows the section, not how the orchestrator runs it.
 */
function SectionDiagram({ text, diagram }: { text: string; diagram: keyof typeof JOURNEY_DIAGRAMS }) {
  return (
    <Demo background>
      <p className="demo-diagram">
        {text}
        <DiagramTrigger spec={JOURNEY_DIAGRAMS[diagram]} label={t('Ablauf "{abschnitt}" als Diagramm anzeigen', { abschnitt: text })} />
      </p>
    </Demo>
  )
}

/**
 * The screens of the logged-in app: a welcome, the person's data, the account's security with its
 * methods, and one method. Each sub-screen leads back one level.
 */
export type AccountView = 'home' | 'profile' | 'security' | 'methods' | { method: string }

interface AuthenticationCompletedViewProps {
  dpop: DpopKeyPair
  channelSessionId: string
  currentAcr?: string
  currentAmr?: string[]
  /** All active methods on the account; currentAmr is only what this session proved. */
  activeMethods?: ActiveMethodView[]
  demo?: DemoInfo
  /**
   * Which screen shows - held by the caller, so a step-up or an added method returns to the
   * screen it was started from rather than to the welcome (this view unmounts meanwhile).
   */
  view: AccountView
  onNavigate: (view: AccountView) => void
  onAddMethod: () => void
  onDeactivateMethod: (methodInstanceId: string) => void
  onStepUp: (requiredAcr: string) => void
  onDeleteAccount: () => void
  onPeerLogin: () => void
  onLogout: () => void
  infoMessage?: string
}

/** A list row like the method choice: icon tile, bold label, grey hint, chevron. */
function MenuRow({
  icon,
  label,
  hint,
  danger,
  onClick,
}: {
  icon: string
  label: string
  hint?: string
  danger?: boolean
  onClick: () => void
}) {
  return (
    <li>
      <button className={danger ? 'method-choice method-choice--danger' : 'method-choice'} onClick={onClick}>
        <span className="method-choice-icon" aria-hidden="true">
          {icon}
        </span>
        <span className="method-choice-text">
          <span className="method-choice-label">{label}</span>
          {hint && <span className="method-choice-hint">{hint}</span>}
        </span>
      </button>
    </li>
  )
}

/** The way back one level, in the phone's row for the ways out. */
function BackTo({ view, onNavigate }: { view: AccountView; onNavigate: (view: AccountView) => void }) {
  return (
    <StepNav>
      <button className="back" onClick={() => onNavigate(view)}>
        {t('Zurück')}
      </button>
    </StepNav>
  )
}

function StatusRow({ label, value }: { label: string; value?: string }) {
  if (value == null || value === '') return null
  return (
    <li>
      <span className="label">{label}</span>
      <span className="value">{value}</span>
    </li>
  )
}

/** FE-11: accountId/personId come from the demo-only object, never a production field. */
export function AuthenticationCompletedView({
  dpop,
  channelSessionId,
  currentAcr,
  currentAmr,
  activeMethods,
  demo,
  view,
  onNavigate,
  onAddMethod,
  onDeactivateMethod,
  onStepUp,
  onDeleteAccount,
  onPeerLogin,
  onLogout,
  infoMessage,
}: AuthenticationCompletedViewProps) {
  // loa2 is the step-up target the demo offers; loa3 comes only from an identification (eID,
  // Nect), never from a step-up - so offering it only makes sense below loa2.
  const canStepUpToLoa2 = isBelowAcr(currentAcr, 'loa2')

  // Who is logged in: real ID-token claims (docs/05-api.md, "ID-Token-Claims"), not the demo-only
  // object. Fetched once when this screen is reached, not part of every response. Without them the
  // screen still works; no error is shown.
  const [claims, setClaims] = useState<IdTokenClaims | undefined>()
  useEffect(() => {
    let active = true
    getIdClaims(dpop, channelSessionId)
      .then((idClaims) => {
        if (active) setClaims(idClaims)
      })
      .catch(() => {
        // Non-fatal - the rest of this screen works fine without the claims.
      })
    return () => {
      active = false
    }
  }, [dpop, channelSessionId])

  const personName = typeof claims?.name === 'string' ? claims.name : undefined
  // The role (ADR-34): versnr = insured with us (Versicherter); personId alone = known to the
  // Personenverzeichnis but not insured here (Partner); neither = prospect (ADR-10/18 - full
  // identity possibly attested, but no person assigned).
  const accountStatus = claims ? accountRole(claims.personId, claims.versnr) : undefined
  const hasQrLogin = activeMethods?.some((m) => m.method === 'qr') ?? true
  const note = infoMessage && <p className="step-context">{infoMessage}</p>

  const home = (
    <>
      <div className="card success-card">
        <h2>{personName ? t('Willkommen, {name}!', { name: personName }) : t('Willkommen!')}</h2>
        <p>{accountStatus ? t('Sie sind als {status} angemeldet.', { status: accountStatus }) : t('Sie sind angemeldet.')}</p>
      </div>
      <ul className="method-choice-list account-menu">
        <MenuRow icon="👤" label={t('Profil')} hint={t('Ihre persönlichen Daten')} onClick={() => onNavigate('profile')} />
        <MenuRow icon="🔒" label={t('Sicherheit')} hint={t('Anmeldeverfahren und Konto')} onClick={() => onNavigate('security')} />
        {/* Worded as an instruction, not a status: the app cannot know whether a browser is
            waiting - the pairing code shown there is what connects the two, entered next. */}
        <MenuRow
          icon="💻"
          label={t('Anmeldung im Browser bestätigen')}
          hint={hasQrLogin ? t('Code aus dem Browser eingeben') : t('Dafür zuerst unter „Sicherheit“ den QR-Login hinzufügen')}
          onClick={onPeerLogin}
        />
      </ul>
      <SectionDiagram text={t('Anmeldung im Browser bestätigen')} diagram="confirmPeerLogin" />
      <div className="form-actions">
        <button className="secondary" onClick={onLogout}>
          {t('Abmelden')}
        </button>
      </div>
    </>
  )

  const profile = (
    <div className="card">
      <BackTo view="home" onNavigate={onNavigate} />
      <h2>{t('Profil')}</h2>
      <ul className="status-list">
        <StatusRow label={t('Vor- und Nachname')} value={personName} />
        <StatusRow label={t('Status')} value={accountStatus} />
        <StatusRow label={t('Mitgliedsnummer')} value={claims?.versnr} />
        <StatusRow label={t('Partnernummer')} value={claims?.personId} />
        <StatusRow label={t('E-Mail')} value={claims?.email} />
      </ul>
      {!claims && <p className="note">{t('Ihre Daten werden geladen …')}</p>}
      {canStepUpToLoa2 && (
        <>
          <h3 className="section-heading">{t('Sicherheitsniveau erhöhen')}</h3>
          <SectionDiagram text={t('Sicherheitsniveau erhöhen')} diagram="stepUp" />
          <p>{t('Bestätigen Sie Ihre Anmeldung mit einem zweiten Verfahren, dann erreichen Sie Sicherheitsniveau 2 - ohne sich neu anzumelden.')}</p>
          <StepActions>
            <button onClick={() => onStepUp('loa2')}>{t('Sicherheitsniveau 2 anfordern')}</button>
          </StepActions>
        </>
      )}
      {(demo?.accountId != null || demo?.personId != null) && (
        <Demo>
          <ul className="status-list">
            <StatusRow label={t('Konto-ID (Demo)')} value={demo?.accountId != null ? String(demo.accountId) : undefined} />
            <StatusRow label={t('Personen-ID (Demo)')} value={demo?.personId != null ? String(demo.personId) : undefined} />
          </ul>
        </Demo>
      )}
      {/* The raw claims next to the phone - the screen itself shows them as a person's data. */}
      {claims && (
        <Demo>
          <Disclosure summary={t('ID-Token-Claims')}>
            <ul className="status-list">
              {Object.entries(claims)
                .filter(([, value]) => value !== null && value !== undefined)
                .map(([key, value]) => (
                  <li key={key}>
                    <span className="label">{key}</span>
                    <span className="value">{Array.isArray(value) ? value.join(', ') : String(value)}</span>
                  </li>
                ))}
            </ul>
          </Disclosure>
        </Demo>
      )}
    </div>
  )

  const methodCount = activeMethods?.length
  const security = (
    <>
      <div className="card">
        <BackTo view="home" onNavigate={onNavigate} />
        <h2>{t('Sicherheit')}</h2>
        {note}
        <p>{t('Hier sehen Sie, wie gut Ihr Konto geschützt ist, und verwalten Ihre Anmeldeverfahren.')}</p>
        <ul className="status-list">
          <StatusRow label={t('Sicherheitsniveau')} value={currentAcr} />
        </ul>
      </div>
      <ul className="method-choice-list account-menu">
        <MenuRow
          icon="🔑"
          label={t('Anmeldeverfahren')}
          hint={methodCount != null ? t('{anzahl} eingerichtet', { anzahl: String(methodCount) }) : undefined}
          onClick={() => onNavigate('methods')}
        />
        <MenuRow icon="🗑️" label={t('Konto löschen')} hint={t('Löscht Ihr Konto und alle Anmeldeverfahren endgültig.')} danger onClick={onDeleteAccount} />
      </ul>
      <SectionDiagram text={t('Konto löschen')} diagram="deleteAccount" />
      {/* What this session proved, as the orchestrator records it - raw values, next to the phone. */}
      {currentAmr && currentAmr.length > 0 && (
        <Demo>
          <ul className="status-list">
            <StatusRow label={t('Genutzte Anmeldeverfahren')} value={currentAmr.join(', ')} />
          </ul>
        </Demo>
      )}
    </>
  )

  // activeMethods is the account's full method list, not the session's evidence (currentAmr): a
  // method not proven this session still shows up here and can be deactivated.
  const methods = (
    <div className="card">
      <BackTo view="security" onNavigate={onNavigate} />
      <h2>{t('Anmeldeverfahren')}</h2>
      {note}
      <p>{t('Mit diesen Verfahren können Sie sich anmelden. Wählen Sie eines, um es zu deaktivieren.')}</p>
      {activeMethods && activeMethods.length > 0 && (
        <ul className="method-choice-list">
          {activeMethods.map((method) => (
            <MenuRow
              key={method.id}
              icon={metaFor(enrollmentToolFor(method.method) ?? method.method).icon}
              label={labelFor(method)}
              hint={method.label ? kindLabel(method) : metaFor(enrollmentToolFor(method.method) ?? method.method).hint}
              onClick={() => onNavigate({ method: method.id })}
            />
          ))}
        </ul>
      )}
      <SectionDiagram text={t('Anmeldeverfahren verwalten')} diagram="manageMethods" />
      {/* The method itself, not only the name: a user-chosen label ("Mein Handy") does not say which
          procedure it is, and several methods can be device-bound. Shown next to the phone, it is
          how the orchestrator sees each one. */}
      {activeMethods && activeMethods.length > 0 && (
        <Demo>
          <ul className="status-list">
            {activeMethods.map((method) => (
              <li key={method.id}>
                <span className="label">{labelFor(method)}</span>
                <span className="value method-detail-hint">
                  {[method.method, acrDetailLabel(method), factorTypesLabel(method)].filter(Boolean).join(' · ')}
                </span>
              </li>
            ))}
          </ul>
        </Demo>
      )}
      <StepActions>
        <button onClick={onAddMethod}>{t('Weiteres Verfahren hinzufügen')}</button>
      </StepActions>
    </div>
  )

  const selected = typeof view === 'object' ? activeMethods?.find((method) => method.id === view.method) : undefined
  const method = selected && (
    <div className="card">
      <BackTo view="methods" onNavigate={onNavigate} />
      <h2>{labelFor(selected)}</h2>
      {note}
      <ul className="status-list">
        <StatusRow label={t('Verfahren')} value={kindLabel(selected)} />
      </ul>
      <p>{t('Nach dem Deaktivieren können Sie sich mit diesem Verfahren nicht mehr anmelden.')}</p>
      <StepActions>
        <button className="destructive" onClick={() => onDeactivateMethod(selected.id)}>
          {t('Deaktivieren')}
        </button>
      </StepActions>
    </div>
  )

  // A method that is gone (deactivated, or the list not loaded yet) shows the list it was on.
  const screen =
    view === 'profile' ? profile : view === 'security' ? security : view === 'methods' ? methods : typeof view === 'object' ? (method ?? methods) : home

  return (
    <>
      {screen}
      <Demo>
        <TokenPanel dpop={dpop} channelSessionId={channelSessionId} />
      </Demo>
    </>
  )
}
