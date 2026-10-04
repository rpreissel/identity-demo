import { ADMIN_PATH, adminAuthHeader, clearAdminCredentials } from './adminAuth'
import type { ToolCatalogEntry } from './generated/models'
import { createDpopProof, type DpopKeyPair } from './dpop'
import type { ActiveMethodView, ChannelResponse, DeviceLinkResponse, ErrorResponse, IdTokenClaims, JourneyTraceResponse, TokenResponse } from './types'
import { ErrorResponseErrorEnum } from './generated/models'
import { resolveText, t } from './texts'
import { toolVersionOf } from './tools/registry'

/**
 * Reads an error body. The shape is the contract's `ErrorResponse`; anything else (a proxy's HTML
 * page, an empty body) falls back to the raw text, so an error never gets lost in parsing.
 * `errorCode` stays a plain string: the contract says a client must expect codes it does not know.
 */
export function parseErrorBody(text: string, fallback: string): { errorCode: string | undefined; message: string } {
  try {
    const parsed = JSON.parse(text) as Partial<ErrorResponse>
    // The server sends a text reference; it becomes words in the reader's language here.
    return { errorCode: parsed.error, message: parsed.text ? resolveText(parsed.text) : fallback }
  } catch {
    return { errorCode: undefined, message: fallback }
  }
}

/** Carries the server's own error/message (docs/07-betrieb.md #1) instead of a raw fetch string. */
export class ApiError extends Error {
  readonly status: number
  readonly errorCode: string | undefined

  constructor(status: number, errorCode: string | undefined, message: string) {
    super(message)
    this.name = 'ApiError'
    this.status = status
    this.errorCode = errorCode
  }
}

/** One request/response round-trip for the debug log. DPoP proof headers are left out on purpose. */
export interface ApiCallLogEntry {
  method: string
  path: string
  requestBody?: unknown
  status?: number
  responseBody?: unknown
  error?: string
}

type ApiCallListener = (entry: ApiCallLogEntry) => void
const apiCallListeners: ApiCallListener[] = []

/** Every `call()` invocation is reported here: the single source of truth for the debug log. */
export function onApiCall(listener: ApiCallListener): () => void {
  apiCallListeners.push(listener)
  return () => {
    const index = apiCallListeners.indexOf(listener)
    if (index !== -1) apiCallListeners.splice(index, 1)
  }
}

function notifyApiCall(entry: ApiCallLogEntry) {
  for (const listener of apiCallListeners) listener(entry)
}

/**
 * A genuine race on the same session's optimistically locked row (two tabs, a doubled effect, a
 * client retry). Retried once here instead of serializing writes server-side: the conflict means
 * the write never applied, so a retry is safe for any HTTP method, and a per-session lock would
 * cost every request for a rare case.
 */
const CONCURRENT_MODIFICATION_RETRY_DELAY_MS = 150

async function call<T>(dpop: DpopKeyPair, method: string, path: string, body?: unknown, isRetry = false): Promise<T> {
  const url = `${window.location.origin}${path}`
  const proof = await createDpopProof(dpop.keyPair, method, url)
  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers: { 'Content-Type': 'application/json', DPoP: proof },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (err) {
    notifyApiCall({ method, path, requestBody: body, error: err instanceof Error ? err.message : String(err) })
    throw err
  }
  if (!response.ok) {
    const text = await response.text()
    const { errorCode, message } = parseErrorBody(text, text || `${method} ${path} failed: ${response.status}`)
    if (!isRetry && errorCode === ErrorResponseErrorEnum.CONCURRENT_MODIFICATION) {
      await new Promise((resolve) => setTimeout(resolve, CONCURRENT_MODIFICATION_RETRY_DELAY_MS))
      return call(dpop, method, path, body, true)
    }
    notifyApiCall({ method, path, requestBody: body, status: response.status, error: message })
    throw new ApiError(response.status, errorCode, message)
  }
  if (response.status === 204) {
    notifyApiCall({ method, path, requestBody: body, status: response.status })
    return undefined as T
  }
  const responseBody = await response.json()
  notifyApiCall({ method, path, requestBody: body, status: response.status, responseBody })
  return responseBody as T
}

/**
 * Always creates a new channel for this device, never a resume (docs/02-domaenenmodell.md #3).
 * [intent] is the backend's AuthIntent name, case-insensitive. Omitted or "fast_access": device
 * link found -> LOGIN, else REGISTRATION. "lookup_login" offers login by email and credential,
 * "register" starts a fresh REGISTRATION (a second account on this device); both also on a linked device.
 */
export function createChannel(
  dpop: DpopKeyPair,
  requiredAcr?: string,
  intent?: string,
  availableTools?: string[],
): Promise<ChannelResponse> {
  // Each tool in the one version this client speaks (ADR-51).
  const body: Record<string, unknown> = { availableTools: availableTools?.map((toolId) => `${toolId}@${toolVersionOf(toolId)}`) }
  if (requiredAcr) body.requiredAcr = requiredAcr
  if (intent) body.intent = intent
  return call(dpop, 'POST', '/orchestrator/api/v1/app/channels', body)
}

/** Whether this device is linked to an account (docs/05-api.md). A pure read, creates no channel. */
export function getDeviceLink(dpop: DpopKeyPair): Promise<DeviceLinkResponse> {
  return call(dpop, 'GET', '/orchestrator/api/v1/app/channels/device-link')
}

export function getChannel(dpop: DpopKeyPair, channelSessionId: string): Promise<ChannelResponse> {
  return call(dpop, 'GET', `/orchestrator/api/v1/channels/${channelSessionId}`)
}

export function raiseRequiredAcr(dpop: DpopKeyPair, channelSessionId: string, requiredAcr: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/channels/${channelSessionId}/step-ups`, { requiredAcr })
}

/** Abandons the running AuthJourney; the response already offers a fresh start where applicable. */
export function cancelJourney(dpop: DpopKeyPair, channelSessionId: string): Promise<ChannelResponse> {
  return call(dpop, 'DELETE', `/orchestrator/api/v1/channels/${channelSessionId}/journey`)
}

/** Starts a LOGOUT journey with a confirmation prompt (interactive clients). */
export function startLogout(dpop: DpopKeyPair, channelSessionId: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/channels/${channelSessionId}/logouts`)
}

/** Direct logout without confirmation (non-interactive clients, hard logout). */
export function logoutChannel(dpop: DpopKeyPair, channelSessionId: string): Promise<void> {
  return call(dpop, 'DELETE', `/orchestrator/api/v1/channels/${channelSessionId}`)
}

/** The account's active authentication methods, addressable as their own resource (docs/05-api.md #2). */
export function getMethods(dpop: DpopKeyPair, channelSessionId: string): Promise<{ methods: ActiveMethodView[] }> {
  return call(dpop, 'GET', `/orchestrator/api/v1/channels/${channelSessionId}/methods`)
}

/**
 * Voluntary enrollment on an AUTHENTICATED channel (AuthIntent.MANAGE): offers the enroll-* tools
 * and finishes after one. Call again to add another.
 */
export function startManageMethods(dpop: DpopKeyPair, channelSessionId: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/channels/${channelSessionId}/enrollments`)
}

/**
 * Confirms a WEB-channel QR login from this AUTHENTICATED channel (AuthIntent.CONFIRM_PEER_LOGIN,
 * docs/04-orchestrierung.md). Gates on loa2 (step-up first if below), then offers approve-qr.
 */
export function startPeerLogin(dpop: DpopKeyPair, channelSessionId: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/channels/${channelSessionId}/peer-logins`)
}

/**
 * Deactivates one active method instance, addressed by its id: a method can have several (e.g.
 * multiple devices). Rejected with 409 if the account would drop below the channel's required level.
 */
export function deactivateMethod(dpop: DpopKeyPair, channelSessionId: string, methodInstanceId: string): Promise<ChannelResponse> {
  return call(dpop, 'DELETE', `/orchestrator/api/v1/channels/${channelSessionId}/methods/${methodInstanceId}`)
}

/**
 * Changes an active method in place (`changeable` in the methods list): its enrollment runs again
 * and the new credential replaces this instance once it is complete.
 */
export function changeMethod(dpop: DpopKeyPair, channelSessionId: string, methodInstanceId: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/channels/${channelSessionId}/methods/${methodInstanceId}/changes`)
}

/**
 * Starts the account-deletion journey on an AUTHENTICATED channel: a yes/no confirmation
 * (PromptView), then a fresh proof of an active factor.
 */
export function startAccountDeletion(dpop: DpopKeyPair, channelSessionId: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/channels/${channelSessionId}/account-deletions`)
}

/**
 * Covers both first issuance and refresh (docs/05-api.md #2) - call again whenever a fresh token
 * might be needed. minValiditySeconds is the caller's tolerance; the backend alone decides
 * whether the current AccessToken still qualifies or a new one gets minted.
 */
export function getToken(dpop: DpopKeyPair, channelSessionId: string, minValiditySeconds?: number): Promise<TokenResponse> {
  const query = minValiditySeconds !== undefined ? `?minValiditySeconds=${minValiditySeconds}` : ''
  return call(dpop, 'GET', `/orchestrator/api/v1/channels/${channelSessionId}/token${query}`)
}

/** The fachliche ID-token claims - a resource separate from the AccessToken's own claims. */
export function getIdClaims(dpop: DpopKeyPair, channelSessionId: string): Promise<IdTokenClaims> {
  return call(dpop, 'GET', `/orchestrator/api/v1/channels/${channelSessionId}/idclaims`)
}

/** Answers the AnswerableState/Prompt the current step waits on (docs/05-api.md, Prompt). */
export function answerPrompt(dpop: DpopKeyPair, channelSessionId: string, accept: boolean): Promise<ChannelResponse> {
  return call(dpop, 'POST', `/orchestrator/api/v1/channels/${channelSessionId}/answer`, { answer: accept ? 'accept' : 'decline' })
}

/** Where `toolId` lives in the one version this client speaks (ADR-51). */
function toolPath(toolId: string): string {
  return `/tools/api/${toolId}/v${toolVersionOf(toolId)}`
}

/** One run of a tool: the path its calls go to, and what a device proof names as `htu`. */
export function toolSessionPath(toolSessionId: string, toolId: string): string {
  return `${toolPath(toolId)}/${toolSessionId}`
}

/**
 * Declines the currently running tool without giving up the journey (docs/04-orchestrierung.md):
 * on a fallback state the chain moves on, on a mandatory one the full choice comes back.
 */
export function abandonTool(dpop: DpopKeyPair, toolSessionId: string, toolId: string): Promise<ChannelResponse> {
  return call(dpop, 'DELETE', toolSessionPath(toolSessionId, toolId))
}

/**
 * "Zurück": leaves the running tool without declining it. The journey shows its selection again,
 * with this tool still among the options. Where there is no selection, the same as [abandonTool].
 */
export function backFromTool(dpop: DpopKeyPair, toolSessionId: string, toolId: string): Promise<ChannelResponse> {
  return call(dpop, 'POST', `${toolSessionPath(toolSessionId, toolId)}/back`)
}

/**
 * toolId always comes from next.toolId or a chosen stepData.options entry, never built by the
 * client. [body] is only used by approve-qr: a known pairing code lets the server skip its
 * `input` step (ConfirmQrLoginToolController).
 */
export function activateTool(
  dpop: DpopKeyPair,
  channelSessionId: string,
  toolId: string,
  body?: Record<string, unknown>
): Promise<ChannelResponse> {
  return call(dpop, 'POST', `${toolPath(toolId)}?channel=${channelSessionId}`, body)
}

export function patchTool(
  dpop: DpopKeyPair,
  toolSessionId: string,
  toolId: string,
  body: Record<string, unknown>
): Promise<ChannelResponse> {
  return call(dpop, 'PATCH', toolSessionPath(toolSessionId, toolId), body)
}

export function getTool(dpop: DpopKeyPair, toolSessionId: string, toolId: string): Promise<ChannelResponse> {
  return call(dpop, 'GET', toolSessionPath(toolSessionId, toolId))
}

/**
 * A POST to a resource a tool defines under its own URL namespace (docs/05-api.md: everything
 * below `/tools/api/{toolId}/v{version}/{toolSessionId}` is the tool's own design). The sub-path
 * belongs to the tool's api.ts; this client only signs and reads a ChannelResponse back.
 */
export function postToolSubResource(
  dpop: DpopKeyPair,
  toolSessionId: string,
  toolId: string,
  subPath: string,
  body: Record<string, unknown>
): Promise<ChannelResponse> {
  return call(dpop, 'POST', `${toolSessionPath(toolSessionId, toolId)}/${subPath}`, body)
}

export type ToolRole =
  | 'KNOWN_ACCOUNT_AUTH'
  | 'ACCOUNT_LOOKUP_AUTH'
  | 'IDENTIFICATION'
  | 'CORRELATION'
  | 'ENROLLMENT'
  | 'ATTESTATION'
  | 'PEER_APPROVAL'

/** One version of a tool and its switch for the channel (ADR-51). */
export interface ToolVersionAvailability {
  /** The version's wire form, `enroll-sms@2`: how the switch is addressed. */
  tool: string
  version: number
  enabled: boolean
  reason?: string | null
}

/** One tool in a channel's order; the order is per tool, the switch per version. */
export interface ToolAvailabilityEntry {
  toolId: string
  method: string
  /** Which kind of selection list the tool appears in - the order only matters within one role. */
  role: ToolRole
  versions: ToolVersionAvailability[]
}

/** APP = App-Kanal, WEB = Web-Kanal. */
export type ChannelType = 'APP' | 'WEB'

/** One channel type's tools, in the order that channel offers them. */
export interface ChannelToolAvailability {
  channel: ChannelType
  tools: ToolAvailabilityEntry[]
}

/**
 * No DPoP: these endpoints (docs/03-tool-architektur.md, availability) aren't bound to a device or
 * channel. Operator endpoints under ADMIN_PATH get the admin login's Basic header instead; a 401
 * there logs the admin page out, so it shows its login form again.
 */
async function callPlain<T>(method: string, path: string, body?: unknown): Promise<T> {
  const admin = path.startsWith(ADMIN_PATH)
  const auth = admin ? adminAuthHeader() : null
  const response = await fetch(path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(auth ? { Authorization: auth } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  })
  if (admin && response.status === 401) clearAdminCredentials()
  if (!response.ok) throw new ApiError(response.status, undefined, `${method} ${path} failed: ${response.status}`)
  // A Kotlin `Unit`-returning controller method (e.g. every admin PUT) answers 200 with an empty
  // body, not 204. Reading as text and parsing only a non-empty body covers both.
  const text = await response.text()
  return (text === '' ? undefined : JSON.parse(text)) as T
}

export function fetchToolCatalog(): Promise<ToolCatalogEntry[]> {
  return callPlain('GET', '/orchestrator/api/v1/tools/catalog')
}

export function fetchToolAvailability(): Promise<ChannelToolAvailability[]> {
  return callPlain('GET', `${ADMIN_PATH}/tools/availability`)
}

/** Switches one tool version (`enroll-sms@2`) on or off for one channel type. */
export function setToolAvailability(tool: string, channel: ChannelType, enabled: boolean, reason?: string): Promise<void> {
  return callPlain('PUT', `${ADMIN_PATH}/tools/${tool}/availability/${channel}`, { enabled, reason })
}

/** First entry is offered first; applies to every selection screen of that channel type. */
export function setToolOrder(channel: ChannelType, toolIds: string[]): Promise<void> {
  return callPlain('PUT', `${ADMIN_PATH}/tools/order/${channel}`, { toolIds })
}

export interface RegistrationOrderState {
  enrollFirst: boolean
}

/**
 * REGISTER's "Enrollment zuerst" experiment (docs/04-orchestrierung.md). Global, takes effect for
 * the next new REGISTER journey.
 */
export function fetchRegistrationOrder(): Promise<RegistrationOrderState> {
  return callPlain('GET', '/orchestrator/admin/registration-order')
}

export function setRegistrationOrder(enrollFirst: boolean): Promise<void> {
  return callPlain('PUT', '/orchestrator/admin/registration-order', { enrollFirst })
}

/** Which login theme Keycloak shows (docs/adr/ADR-041-keycloakify-neben-freemarker.md). */
export type LoginTheme = 'FREEMARKER' | 'KEYCLOAKIFY'

/** Only under the server's `keycloak` profile - 404s otherwise. */
export function fetchLoginTheme(): Promise<{ theme: LoginTheme }> {
  return callPlain('GET', `${ADMIN_PATH}/login-theme`)
}

/** Realm-wide, from the next page Keycloak renders. */
export function setLoginTheme(theme: LoginTheme): Promise<void> {
  return callPlain('PUT', `${ADMIN_PATH}/login-theme`, { theme })
}

/**
 * The same realm-wide switch without an admin login (DemoLoginThemeController) - the website's demo
 * column offers it to every visitor, so the two themes can be compared where the pages open.
 */
export function setDemoLoginTheme(theme: LoginTheme): Promise<void> {
  return callPlain('PUT', '/orchestrator/demo/login-theme', { theme })
}

/** What the web channel's loa1 asks for (docs/adr/ADR-042-loa1-anmeldung-umschalten.md). */
export type Loa1Login = 'KEYCLOAK_PASSWORD' | 'ORCHESTRATOR'

/** Only under the server's `keycloak` profile - 404s otherwise. */
export function fetchLoa1Login(): Promise<{ login: Loa1Login }> {
  return callPlain('GET', `${ADMIN_PATH}/loa1-login`)
}

/** Realm-wide, from the next sign-in on. */
export function setLoa1Login(login: Loa1Login): Promise<void> {
  return callPlain('PUT', `${ADMIN_PATH}/loa1-login`, { login })
}

/** The same realm-wide switch without an admin login (DemoLoa1LoginController), for the website's demo column. */
export function setDemoLoa1Login(login: Loa1Login): Promise<void> {
  return callPlain('PUT', '/orchestrator/demo/loa1-login', { login })
}

/** Same shape as JourneyTraceResponse, plus who each account id is (register display name). */
export interface AdminJourneyTraceResponse extends JourneyTraceResponse {
  accounts: { accountId: number; displayName?: string | null }[]
}

export function fetchAdminJourneyTrace(limit = 500): Promise<AdminJourneyTraceResponse> {
  return callPlain('GET', `${ADMIN_PATH}/journey-trace?limit=${limit}`)
}

export interface AdminAccount {
  accountId: number
  personId?: number | null
  displayName?: string | null
  email?: string | null
  methods: string[]
}

export function fetchAdminAccounts(): Promise<AdminAccount[]> {
  return callPlain('GET', `${ADMIN_PATH}/accounts`)
}

export function deleteAdminAccount(accountId: number): Promise<void> {
  return callPlain('DELETE', `${ADMIN_PATH}/accounts/${accountId}`)
}

export function resetDemo(): Promise<{ deletedAccounts: number; endedSessions: number }> {
  return callPlain('POST', `${ADMIN_PATH}/demo-reset`)
}

/** The same reset without admin login (DemoSessionsController), for the welcome page. */
export function resetDemoPublic(): Promise<{ deletedAccounts: number; endedSessions: number }> {
  return callPlain('POST', '/orchestrator/demo/reset')
}

export type ChannelStateName =
  | 'ANONYMOUS'
  | 'REGISTERING'
  | 'AUTHENTICATED'
  | 'STEP_UP_REQUIRED'
  | 'STEP_UP_IN_PROGRESS'
  | 'LOGGED_OUT'
  | 'EXPIRED'

/** One live orchestrator channel (ActiveSessions.kt). Times are ISO instants. */
export interface ActiveChannel {
  channelSessionId: string
  channel: ChannelType
  state: ChannelStateName
  accountId?: number | null
  displayName?: string | null
  createdAt: string
  lastAccessedAt: string
  expiresAt: string
}

/** One open Keycloak session, with the orchestrator channel it belongs to if known. */
export interface KeycloakSession {
  sessionId: string
  username?: string | null
  accountId?: number | null
  displayName?: string | null
  start: string
  lastAccess: string
  channelSessionId?: string | null
  channelState?: ChannelStateName | null
}

export interface ActiveSessionsReport {
  channels: {
    total: number
    perType: { channel: ChannelType; count: number }[]
    /** The ten newest, newest first. */
    newest: ActiveChannel[]
  }
  /** null/absent without the `keycloak` profile; `clients` is empty when `error` is set. */
  keycloak?: {
    clients: { client: 'WEBSITE' | 'APP'; clientId: string; count: number; newest: KeycloakSession[] }[]
    error?: string | null
  } | null
}

export function fetchAdminSessions(): Promise<ActiveSessionsReport> {
  return callPlain('GET', `${ADMIN_PATH}/sessions`)
}

/** Public, read-only - what the welcome page shows before a reset. */
export function fetchDemoSessions(): Promise<ActiveSessionsReport> {
  return callPlain('GET', '/orchestrator/demo/sessions')
}

/** Probes the stored credentials - any admin GET does; 401 clears them (see callPlain). */
export function checkAdminLogin(): Promise<unknown> {
  return fetchRegistrationOrder()
}

/** The real Keycloak as this browser reaches it - only under the server's `keycloak` profile. */
export interface KeycloakInfo {
  /** Public address (what the browser resolves), not the server-to-server one. */
  baseUrl: string
  realm: string
  browserClientId: string
  loginTheme: LoginTheme
  loa1Login: Loa1Login
}

export interface ServerInfo {
  /** null/absent without the `keycloak` profile - then there is no Web channel. */
  keycloak?: KeycloakInfo | null
  registrationEnrollFirst: boolean
  /** Switched-off tool versions, `tool` in its wire form (`enroll-sms@2`). */
  disabledTools: { tool: string; channel: ChannelType; reason?: string | null }[]
  /** Demo mode - among other things, responses carry the demo values (TANs, personas). */
  demoMode: boolean
  /** Health and metrics of the actuator (management port), read by the backend; null outside demo mode. */
  operations?: OperationsInfo | null
}

export interface OperationsInfo {
  status: string
  components: { name: string; status: string }[]
  /** `dpop.*` meters per tag set; `http.client.requests` per host with `meanMillis`. */
  metrics: { name: string; tags: Record<string, string>; value: number; meanMillis?: number | null }[]
}

/** Public, read-only (no login) - the welcome page's "Server-Status" tab. */
export function fetchServerInfo(): Promise<ServerInfo> {
  return callPlain('GET', '/orchestrator/demo/server-info')
}

/**
 * Renders any thrown error into the UI's error card. ApiErrors carry the server's message
 * (docs/07-betrieb.md #1). PROCESS_GONE (session expired or consumed) also gets a concrete next step.
 *
 * Only PROCESS_GONE, not every 410: PROCESS_ABORTED means the server ended the process on purpose,
 * and its message already says what to do.
 */
export function describeError(prefix: string, err: unknown): string {
  // A wording may end in its own full stop ("Der Start hat nicht geklappt."), so no "geklappt.:".
  prefix = prefix.replace(/[.:]\s*$/, '')
  if (err instanceof ApiError) {
    const hint = err.errorCode === ErrorResponseErrorEnum.PROCESS_GONE
      ? ' ' + t('Bitte in der Demo-Spalte unter „Aktionen der Demo“ auf „Sitzung vergessen“ klicken, um neu zu starten.')
      : ''
    return `${prefix}: ${err.message}${hint}`
  }
  return `${prefix}: ${err instanceof Error ? err.message : String(err)}`
}
