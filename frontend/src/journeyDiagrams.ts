import type { JourneyDiagramCurrentStep, JourneyDiagramSpec } from './components/JourneyDiagram'
import type { JourneyDebugStep } from './types'
import { t } from './texts'

/**
 * The representative shape of each entry journey, shared by the start-screen previews and the
 * in-progress hint (JourneyStructureView). A shape, not a spec: the backend decides the real order
 * case by case (docs/04-orchestrierung.md). Only the branch each argument hinges on is drawn.
 */
export const JOURNEY_DIAGRAMS: Record<
  | 'channel'
  | 'auto'
  | 'register'
  | 'registerEnrollFirst'
  | 'login'
  | 'stepUp'
  | 'manageMethods'
  | 'deleteAccount'
  | 'reIdentify'
  | 'confirmPeerLogin'
  | 'webLoginLoa1'
  | 'webLoginLoa2'
  | 'webLoginInvite',
  JourneyDiagramSpec
> = {
  channel: {
    title: t('Channel-Lebenszyklus'),
    // The enum values shown in the Channel box's state field (ChannelState.kt). Every channel
    // starts ANONYMOUS. REGISTERING is derived, never stored: it shows an account still being set
    // up (docs/adr/ADR-046), from the first proof of a registration until its first login method;
    // then the channel is ANONYMOUS again until it logs in. Step-up is
    // a loop back onto AUTHENTICATED, not drawn here; the stepUp diagram covers it. AUTHENTICATED
    // opens the one Keycloak session, and the channel ends no later than it (docs/adr/ADR-043).
    steps: [
      t('Konto schon eingerichtet?'),
      t('{zustand}, Keycloak-Sitzung beginnt', { zustand: 'AUTHENTICATED' }),
      t('{zustand}, spätestens mit der Keycloak-Sitzung', { zustand: 'LOGGED_OUT / EXPIRED' }),
    ],
    branch: {
      atIndex: 0,
      mainLabel: t('Ja ({zustand})', { zustand: 'ANONYMOUS' }),
      label: t('Nein, Konto im Aufbau ({zustand})', { zustand: 'REGISTERING' }),
      steps: [
        t('Erstes Anmeldeverfahren, Konto eingerichtet ({zustand})', { zustand: 'ANONYMOUS' }),
        t('{zustand}, Keycloak-Sitzung beginnt', { zustand: 'AUTHENTICATED' }),
      ],
    },
  },
  auto: {
    title: t('Automatisch anmelden'),
    steps: [t('Gerät erkannt?'), t('Faktor bestätigen'), t('Angemeldet')],
    branch: {
      atIndex: 0,
      mainLabel: t('Ja'),
      label: t('Nein'),
      steps: [t('Identifikation'), t('E-Mail bestätigen'), t('2. Faktor einrichten'), t('Angemeldet')],
    },
  },
  register: {
    title: t('Neues Konto anlegen'),
    // Reihenfolge nach ADR-17 (docs/12-entscheidungen.md): Die Adresse ist Konto-Infrastruktur und
    // wird vor jedem Anmeldeverfahren bestätigt, nicht als dessen Nebenprodukt danach.
    steps: [t('Identifikation'), t('E-Mail bestätigen'), t('2. Faktor einrichten'), t('Angemeldet')],
  },
  registerEnrollFirst: {
    title: t('Neues Konto anlegen (Einrichtung zuerst, Experiment)'),
    // RegisterEnrollFirstState (admin-umschaltbar, AdminRegistrationOrderView): eigene Zustände
    // (EnrollFirst*), mit RegisterState teilt es nur RE_IDENTIFY am Ende. Feste Reihenfolge: E-Mail,
    // SMS, ein Verfahren anderer Art (alle Pflicht), erst danach optional identifizieren.
    steps: [t('E-Mail bestätigen'), t('SMS einrichten'), t('Verfahren anderer Art einrichten'), t('Angemeldet')],
    branch: {
      atIndex: 2,
      mainLabel: t('Nein'),
      label: t('optional identifizieren'),
      steps: [t('Identifikation'), t('Angemeldet')],
    },
  },
  login: {
    title: t('Mit E-Mail-Adresse anmelden'),
    steps: [t('E-Mail + Code/Passwort'), t('Gerät merken? (optional)'), t('Angemeldet')],
  },
  stepUp: {
    title: t('Sicherheitsniveau erhöhen'),
    steps: [t('Verfahren wählen'), t('Faktor bestätigen'), t('Niveau erreicht')],
    // The one-method dead end (StepUpStrategy): an account with a single active auth method has
    // nothing left to combine with, so re-identification is the way out instead of a dead end.
    branch: {
      atIndex: 0,
      mainLabel: t('vorhanden'),
      label: t('nur 1 Verfahren'),
      steps: [t('Erneut identifizieren'), t('Niveau erreicht')],
    },
  },
  manageMethods: {
    title: t('Anmeldeverfahren verwalten'),
    // Every wish (add, change, remove) clears the level first, then needs a proof of the last five
    // minutes (ManageAuthMethodsStrategy). A step-up on the "Nein" branch already is that proof.
    steps: [t('Niveau ausreichend?'), t('Nachweis frisch? Sonst bestätigen'), t('Einrichten, ändern oder entfernen'), t('Fertig')],
    branch: {
      atIndex: 0,
      mainLabel: t('Ja'),
      label: t('Nein'),
      steps: [t('Step-up (Faktor bestätigen)'), t('Einrichten, ändern oder entfernen'), t('Fertig')],
    },
  },
  reIdentify: {
    title: t('Erneut identifizieren'),
    // The ReIdentifyState names, shared by FAST_ACCESS, LOOKUP_LOGIN and STEP_UP. Always this
    // confirmation first, never a silent fallback; the identification only confirms the known account.
    steps: ['OfferReIdent', 'Identifying', 'Finished'],
    branch: {
      atIndex: 0,
      mainLabel: t('Ja'),
      label: t('Nein'),
      steps: ['Cancel'],
    },
  },
  confirmPeerLogin: {
    title: t('Anmeldung im Browser bestätigen'),
    // Same anti-self-escalation gate as manageMethods: loa2 first. The "Nein" branch covers both
    // starting points, a cold entry and a loa1 channel; both run the same STEP_UP sub-journey.
    // There is no separate "log in first" step, and never identification or registration
    // (AuthIntent.CONFIRM_PEER_LOGIN).
    steps: [t('Bereits bei loa2?'), t('Web-Login bestätigen'), t('Bestätigt')],
    branch: {
      atIndex: 0,
      mainLabel: t('Ja'),
      label: t('Nein'),
      steps: [t('Anmelden bzw. Step-up (Faktor bestätigen)'), t('Web-Login bestätigen'), t('Bestätigt')],
    },
  },
  deleteAccount: {
    title: t('Konto löschen'),
    // The yes/no confirmation always comes first; the loa2 gate applies only once accepted
    // (DeleteAccountStrategy). A step-up there already is the fresh proof of a factor, and so is
    // any proof of the last five minutes: the re-confirmation step is then skipped.
    steps: [t('Löschen bestätigen'), t('Niveau ausreichend?'), t('Faktor erneut bestätigen'), t('Gelöscht')],
    branch: {
      atIndex: 1,
      mainLabel: t('Ja'),
      label: t('Nein'),
      steps: [t('Step-up (Faktor bestätigen)'), t('Gelöscht')],
    },
  },
    // Kein Journey-Schritt des Orchestrators: Der Browser spricht hier nur mit Keycloak (webOidc.ts).
    // Als Diagramm gezeigt, damit der Web-Kanal-Einstieg dieselbe Vorschau wie der App-Kanal bekommt.
  webLoginLoa1: {
    title: t('Login (loa1)'),
    steps: [t('Redirect zu Keycloak'), t('Login (Passwort oder Code)'), t('Zurück mit AccessToken (loa1)')],
  },
  webLoginLoa2: {
    title: t('Login (loa2)'),
    steps: [t('Redirect zu Keycloak'), t('Login + 2. Faktor'), t('Zurück mit AccessToken (loa2)')],
  },
  // Process access by one-time password (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md).
  webLoginInvite: {
    title: t('Vorgang mit Einmalkennwort'),
    steps: [t('Redirect zu Keycloak'), t('Nummer + Einmalkennwort aus dem Brief'), t('Zurück mit AccessToken (nur dieser Vorgang)')],
  },
}

/**
 * Which diagram box a running journey's `stateType` (JourneyDebugStep.stateType) corresponds to.
 * Matched by hand against the shape above, so several real states can point at the same box (e.g.
 * the wishes in manageMethods). States without an entry get no highlight.
 */
export const CURRENT_STEP_BY_STATE_TYPE: Partial<Record<keyof typeof JOURNEY_DIAGRAMS, Record<string, JourneyDiagramCurrentStep>>> = {
  auto: {
    Start: { index: 0 },
    PreferredAuth: { index: 1 },
    AuthChoice: { index: 1 },
    Identifying: { branch: true, index: 0 },
    ConfirmingEmail: { branch: true, index: 1 },
    Enrolling: { branch: true, index: 2 },
    SecondFactorKindObligation: { branch: true, index: 2 },
  },
  register: {
    Identifying: { index: 0 },
    ConfirmingEmail: { index: 1 },
    Enrolling: { index: 2 },
    SecondFactorKindObligation: { index: 2 },
  },
  registerEnrollFirst: {
    EnrollFirstStart: { index: 0 },
    EnrollFirstAttestingEmail: { index: 0 },
    EnrollFirstEnrollingSms: { index: 1 },
    EnrollFirstEnrolling: { index: 1 },
    EnrollFirstConfirmingEmail: { index: 0 },
    EnrollFirstSecondFactorKindObligation: { index: 2 },
  },
  login: {
    Start: { index: 0 },
    Credential: { index: 0 },
    AdditionalFactor: { index: 0 },
    OfferBinding: { index: 1 },
  },
  stepUp: {
    Start: { index: 0 },
    AuthChoice: { index: 1 },
  },
  manageMethods: {
    AddRequested: { index: 0 },
    ChangeRequested: { index: 0 },
    RemoveRequested: { index: 0 },
    RetractAttributeRequested: { index: 0 },
    ConfirmationRequired: { index: 1 },
    Enrolling: { index: 2 },
    Changing: { index: 2 },
  },
  confirmPeerLogin: {
    Requested: { index: 0 },
    Confirming: { index: 1 },
  },
  deleteAccount: {
    ConfirmPending: { index: 0 },
    ConfirmationRequired: { index: 2 },
  },
  reIdentify: {
    OfferReIdent: { index: 0 },
    Identifying: { index: 1 },
  },
}

/** JourneyDebugStep.intent (AuthIntent name) -> JOURNEY_DIAGRAMS key, one per intent. */
export const INTENT_DIAGRAM_KEY: Record<string, keyof typeof JOURNEY_DIAGRAMS> = {
  FAST_ACCESS: 'auto',
  REGISTER: 'register',
  LOOKUP_LOGIN: 'login',
  STEP_UP: 'stepUp',
  MANAGE_AUTH_METHODS: 'manageMethods',
  DELETE_ACCOUNT: 'deleteAccount',
  RE_IDENTIFY: 'reIdentify',
  CONFIRM_PEER_LOGIN: 'confirmPeerLogin',
}

/**
 * REGISTER has two runtime-toggled strategies sharing one AuthIntent (AdminRegistrationOrderView).
 * Only the reported `stateType` tells them apart: RegisterEnrollFirstState's states are prefixed
 * `EnrollFirst*`. Every other intent maps 1:1 via INTENT_DIAGRAM_KEY.
 */
export function diagramKeyForState(intent: string, stateType: string): keyof typeof JOURNEY_DIAGRAMS | undefined {
  if (intent === 'REGISTER' && stateType.startsWith('EnrollFirst')) return 'registerEnrollFirst'
  return INTENT_DIAGRAM_KEY[intent]
}

/**
 * Which JOURNEY_DIAGRAMS entry describes what runs right now: the innermost journey's intent, as
 * the backend reports it. Never the entry the user clicked: a channel runs several journeys one
 * after another. Used for JourneyStructureView's hints and the context line, so both agree.
 */
export function currentJourneyDiagramKey(journeys: JourneyDebugStep[] | undefined): keyof typeof JOURNEY_DIAGRAMS | undefined {
  const innermost = journeys?.at(-1)
  return innermost ? diagramKeyForState(innermost.intent, innermost.stateType) : undefined
}

/**
 * User-facing text for the app's context banner. JOURNEY_DIAGRAMS.title is a developer-facing
 * caption for the diagram popover; only reIdentify needs a different phrasing here.
 */
export function journeyContextLabel(key: keyof typeof JOURNEY_DIAGRAMS): string {
  if (key === 'reIdentify') return t('Identität erneut bestätigen')
  return JOURNEY_DIAGRAMS[key].title
}
