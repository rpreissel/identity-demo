/**
 * The client's view of the API. Everything on the wire comes from `./generated/models`, generated
 * from `api/openapi.yaml`, which `OpenApiSnapshotTest` keeps in step with the backend. A removed,
 * renamed or newly nullable field becomes a TypeScript error, not an `undefined` at render time.
 * Requiredness comes along too (`KotlinRequiredModelConverter`). Added here: the way out for an
 * unknown `stepData` shape ([StepData]) and what the backend serves as an open map (the demo
 * bag's named extras, the token shapes).
 */
import type * as Wire from './generated/models'

export type {
  ActiveMethodView,
  BoundCredentialView,
  ChannelBlock,
  DeviceLinkResponse,
  ErrorResponse,
  JourneyDebugStep,
  Next,
} from './generated/models'

/** Every step shape this build knows, generated from the contract. */
export type KnownStepData = Wire.StepData
export type StepKind = KnownStepData['kind']

/**
 * A shape this build does not know yet. The backend decides the step flow and may send a `kind`
 * this union has never heard of; nothing may break on it. No index signature: reading a field
 * from an unknown shape is the guess the typed union is there to stop.
 */
export interface UnknownStepData {
  kind: string
}

export type StepData = KnownStepData | UnknownStepData

/**
 * The step data if it has the given shape, otherwise undefined. The one way to read `stepData`:
 * a plain `stepData.kind === 'x'` does not narrow, because [UnknownStepData] matches any string.
 */
export function stepDataOf<K extends StepKind>(
  stepData: StepData | undefined,
  kind: K,
): Extract<KnownStepData, { kind: K }> | undefined {
  return stepData?.kind === kind ? (stepData as Extract<KnownStepData, { kind: K }>) : undefined
}

/** What an AnswerableState shows while it waits - authored by the backend, see PromptView. */
export type Prompt = Wire.Prompt
export type ConfirmPrompt = Wire.Confirm

/**
 * The prompt as a yes/no confirmation, or undefined for a kind this build does not know - the
 * same rule as [UnknownStepData]: an unknown shape is not rendered, it is not guessed at.
 */
export function confirmPromptOf(prompt: Prompt | undefined): ConfirmPrompt | undefined {
  return prompt?.kind === 'Confirm' ? (prompt as ConfirmPrompt) : undefined
}

/**
 * One person of the (simulated) register - travels inside the demo bag, so it has no schema of its
 * own. Read live from the register, so anything but the Partnernummer may be missing (null) for a
 * person created on /personenverzeichnis/.
 */
export interface DemoPerson {
  /** The Partnernummer - every person has one (ADR-34). */
  personId: string
  /** Only for a person insured with us, and even then possibly missing for a while. */
  kvnr?: string | null
  familyName?: string | null
  givenNames?: string | null
  email?: string | null
  /** The mobile number as the register keeps it. */
  phoneNumber?: string | null
  /** Street and house number in one line - as the eID card shows it, not as the register splits it. */
  streetAddress?: string | null
  postalCode?: string | null
  locality?: string | null
  birthDate?: string | null
  /** Plaintext of the newest valid letter in the register's mailbox (ADR-31), null when none is valid. */
  fscCode?: string | null
  /** The eID card's fixed restricted identifier - person-unique, changes only with a new card. */
  restrictedId?: string | null
}

/**
 * Demo-only values, never part of the production contract (docs/05-api.md #1). The generated type
 * carries `accountId`/`personId`/`journeys` plus an open index signature: the backend flattens
 * whatever the tool that just ran attached (@JsonAnyGetter) onto this same object. The named
 * extras below are the ones the UI actually reads, typed so a rename is noticed.
 */
export type DemoInfo = Wire.DemoInfo & {
  /** The just-issued TAN, shown so testers don't need server-log access. */
  tan?: string
  /** Fixed demo password (same for enroll/login/lookup), prefilled for testers. */
  password?: string
  /** Every register person, attached centrally by the orchestrator; a picker wherever a value is prefilled. */
  persons?: DemoPerson[]
}

/**
 * The one response envelope for every endpoint, channel- and tool-level alike (docs/05-api.md #1).
 * Only `stepData` and `demo` are re-typed: `stepData` widened by [UnknownStepData], `demo` by
 * the named extras above.
 */
export type ChannelResponse = Omit<Wire.ChannelResponse, 'stepData' | 'demo'> & {
  stepData?: StepData
  demo?: DemoInfo
}

/**
 * One row of the per-step journey trace (admin page, `/orchestrator/admin/journey-trace/...`),
 * distinct from the account's change log. For debugging and the demo. Hand-written, like the
 * types below: operator endpoints are not part of the app contract.
 */
/** What one trace line records (backend `JourneyTraceEvent`). */
export type JourneyTraceEvent =
  | 'STARTED'
  | 'ANSWERED'
  | 'ACTION_COMPLETED'
  | 'TOOL_COMPLETED'
  | 'TOOL_ABANDONED'
  | 'OFFER_EXHAUSTED'
  | 'SUB_JOURNEY_FINISHED'
  | 'SUB_JOURNEY_CANCELLED'
  | 'EVIDENCE_TAKEN_OVER'
  | 'TOOL_ACTIVATED'
  | 'TOOL_FAILED'
  | 'BACK_TO_SELECTION'
  | 'INVITATION_REFUSED'
  | 'JOURNEY_CANCELLED'
  | 'LOGGED_OUT'
  | 'CHANNEL_EXPIRED'

export interface JourneyTraceEntryView {
  channelSessionId: string
  /** APP or WEB. */
  channelType?: string
  /** Inherited from a later entry of the same channel when logged before the account was bound. */
  accountId?: number
  journeyId?: string
  parentJourneyId?: string
  intent?: string
  eventType: JourneyTraceEvent
  journeyState?: string
  detail: Record<string, unknown>
  createdAt: string
}

/** How a journey ended, read by the backend from its last entry (JourneyTraceOutcome). */
export type JourneyTraceOutcome = 'SUCCEEDED' | 'FAILED' | 'CANCELLED' | 'LOGGED_OUT' | 'OPEN'

/** One journey as a line of the search result. */
export interface JourneyTraceSummary {
  journeyId: string
  /** Set when this journey ran as another's precondition. */
  parentJourneyId?: string
  channelSessionId: string
  channelType?: string
  accountId?: number
  displayName?: string | null
  intent: string
  startedAt: string
  lastActivityAt: string
  stepCount: number
  failedAttempts: number
  outcome: JourneyTraceOutcome
}

/** What the operator searches for (`GET .../journey-trace/journeys`). */
export interface JourneyTraceSearch {
  accountId?: number
  channelSessionId?: string
  journeyId?: string
  intent?: string
  outcome?: JourneyTraceOutcome
  from?: string
  to?: string
  cursor?: string
  limit?: number
}

/**
 * Intent and outcome filter after reading, so a page may hold fewer than asked: [searchedUntil]
 * says how far back it got, [nextCursor] continues there (absent when nothing older is left).
 */
export interface JourneyTracePage {
  journeys: JourneyTraceSummary[]
  nextCursor?: string | null
  searchedUntil?: string | null
}

/** What an account lookup value matched (`POST .../journey-trace/account-lookups`). */
export type AccountMatchKind = 'EMAIL' | 'KVNR' | 'PARTNER_NUMBER' | 'MEMBER_NUMBER' | 'ACCOUNT_NUMBER'

export interface AccountMatch {
  accountId: number
  displayName?: string | null
  matchedBy: AccountMatchKind
}

/** Empty when no account holds the value; more than one when eight digits match two ways. */
export interface AccountLookup {
  matches: AccountMatch[]
}

/** The three roles of an account: insured with us, known as a person, or neither. */
export type AccountRole = 'INSURED' | 'PARTNER' | 'PROSPECT'

/** A value the account is found by, unique across accounts. */
export interface AccountAnchor {
  attributeType: string
  value: string
  establishedAcr?: string | null
  establishedAt: string
}

/** A withdrawn value; it carries no value of its own. */
export interface AccountRetraction {
  id: number
  attributeType: string
  /** ACCOUNT_MANAGEMENT, ACCOUNT_HOLDER, PERSON_DIRECTORY or OPERATOR. */
  source: string
  reason?: string | null
  retractedAt: string
}

/** One entry of the claim log; [maskedValue] shows the first characters, [readable] is false once the value is erased. */
export interface AccountClaim {
  id: number
  attributeType: string
  maskedValue?: string | null
  readable: boolean
  /** `person_directory`, `self-reported` or the id of the tool that proved it. */
  claimSource?: string | null
  establishedAcr?: string | null
  /** The method whose enrollment asserted it; absent for identification. */
  method?: string | null
  establishedAt: string
  retraction?: AccountRetraction | null
}

export interface AccountMethod {
  id: string
  method: string
  label?: string | null
  active: boolean
  enrolledUnderAcr?: string | null
  boundKeyRef?: string | null
  createdAt?: string | null
  deactivatedAt?: string | null
}

export interface AccountDevice {
  bindingKeyRef: string
  linkedAt: string
  lastLinkedAt: string
}

export interface AccountLogEntry {
  changeType?: string
  signInType?: string
  subject?: string | null
  channel?: string | null
  acr?: string | null
  details: Record<string, unknown>
  occurredAt: string
}

/** `GET /orchestrator/admin/accounts/{id}/details`. */
export interface AccountDetails {
  accountId: number
  displayName?: string | null
  role: AccountRole
  createdAt?: string | null
  anchors: AccountAnchor[]
  claims: AccountClaim[]
  retractions: AccountRetraction[]
  methods: AccountMethod[]
  devices: AccountDevice[]
  changeLog: AccountLogEntry[]
  signIns: AccountLogEntry[]
}

/** Every entry of one channel, oldest first. */
export interface JourneyTraceChannel {
  channelSessionId: string
  channelType?: string
  accountId?: number
  displayName?: string | null
  entries: JourneyTraceEntryView[]
  /** One summary per journey in entries, by the same rule as the search. */
  journeys: JourneyTraceSummary[]
  /** More entries than one answer carries; the oldest are shown. */
  truncated: boolean
}

/**
 * App-Kanal AccessToken (docs/05-api.md #3a): von Keycloak signiert, in den Browsertests ein
 * unsecured JWT (alg=none) des Test-Ersatzes; hier nur zum Anzeigen geparst, nie verifiziert.
 * refreshToken is not part of this shape: it is a credential and never leaves the backend, only
 * its expiry does.
 */
export type { TokenResponse } from './generated/models'

/** Fachliche ID-Token-Claims - a resource separate from the AccessToken's own claims. */
export interface IdTokenClaims {
  sub?: string
  acr?: string
  amr?: string[]
  auth_time?: number
  accountId?: number
  /** The Partnernummer (`P` and nine digits) of the person behind this account. */
  personId?: string
  /** Versicherungsnummer - only for a person insured with us; with personId it gives the role (ADR-34). */
  versnr?: string
  /** "Vorname Name" of the person behind this account (PersonDirectory.displayName) - who is logged in. */
  name?: string
  email?: string
  email_verified?: boolean
  [key: string]: unknown
}
