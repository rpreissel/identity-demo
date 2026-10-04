import { language } from './texts'
import type { KeycloakInfo } from './api'
import { t } from './texts'

/**
 * The Web-Kanal demo UI's own OIDC client (docs/05-api.md Abschnitt 3b): a real browser redirect to
 * the real Keycloak, authorization_code + PKCE (S256), token exchange here in the frontend. The
 * `identity-demo-web` client is public (keycloak-migrations, V1__realm.kc.kts), so no client secret ships in the
 * bundle; PKCE makes the code safe to redeem. Keycloak talks to the orchestrator server-to-server.
 * This module is the other direction: the browser acting as the RP.
 */

/**
 * Wo Keycloak für den Browser liegt, welches Realm, welcher Client: vom Server (ServerInfo.keycloak),
 * abgeleitet aus denselben Setup-Parametern, aus denen die Migration Realm und Client anlegt. So
 * passt der Web-Kanal zu jeder Umgebung (anderer Port, OpenShift-Route, KEYCLOAK_REALM).
 */
export type WebOidcConfig = KeycloakInfo

const SESSION_STORAGE_KEY = 'web-kanal-oidc'

export interface TokenSet {
  accessToken: string
  idToken?: string
  refreshToken?: string
  expiresAt: number
}

interface StoredVerifier {
  codeVerifier: string
  redirectUri: string
}

function redirectUri(): string {
  // The path itself doesn't matter (valid_redirect_uris allows the whole origin). Keycloak must send
  // the browser back to this tab, so the code-exchange effect can read `code` from `window.location`.
  return `${window.location.origin}${window.location.pathname}#web`
}

async function sha256Base64Url(input: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(input))
  return base64UrlEncode(new Uint8Array(digest))
}

function base64UrlEncode(bytes: Uint8Array): string {
  let binary = ''
  for (const b of bytes) binary += String.fromCharCode(b)
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '')
}

function randomString(length: number): string {
  const bytes = new Uint8Array(length)
  crypto.getRandomValues(bytes)
  return base64UrlEncode(bytes)
}

/** Keycloak came back with `?error=...` instead of a code; `cancelled` is the user's "Abbrechen". */
export class LoginNotCompletedError extends Error {
  readonly cancelled: boolean

  constructor(error: string, description: string | null) {
    const cancelled = error === 'access_denied'
    super(cancelled ? t('Anmeldung abgebrochen.') : t('Anmeldung fehlgeschlagen: {grund}', { grund: description ?? error }))
    this.name = 'LoginNotCompletedError'
    this.cancelled = cancelled
  }
}

/**
 * Keycloak kennt die Sitzung hinter dem Refresh-Token nicht mehr: abgemeldet, abgelaufen oder das
 * Konto wurde geloescht. Kein Fehler des Aufrufs, sondern das Ende der Anmeldung in diesem Tab.
 */
export class SessionEndedError extends Error {
  constructor() {
    super(t('Ihre Anmeldung ist beendet. Bitte melden Sie sich erneut an.'))
    this.name = 'SessionEndedError'
  }
}

export type WebOidc = ReturnType<typeof createWebOidc>

/** The OIDC client for exactly this Keycloak/realm - every endpoint below is derived from [config]. */
export function createWebOidc(config: WebOidcConfig) {
  const realmBase = `${config.baseUrl}/realms/${encodeURIComponent(config.realm)}/protocol/openid-connect`

  /**
   * Redirects the browser to Keycloak's login. `acrValue` picks the LoA-1/LoA-2 Condition-LoA branch
   * (keycloak-migrations, V1__realm.kc.kts), which the extension forwards to the orchestrator as targetAcr.
   */
  async function redirectToLogin(acrValue: '1' | '2') {
    const codeVerifier = randomString(64)
    const codeChallenge = await sha256Base64Url(codeVerifier)
    const uri = redirectUri()
    sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ codeVerifier, redirectUri: uri } satisfies StoredVerifier))

    const url = new URL(`${realmBase}/auth`)
    url.searchParams.set('client_id', config.browserClientId)
    url.searchParams.set('redirect_uri', uri)
    url.searchParams.set('response_type', 'code')
    // Keycloak's pages in the language chosen in the demo (LanguageSwitch), not only the browser's.
    url.searchParams.set('ui_locales', language())
    url.searchParams.set('scope', 'openid')
    url.searchParams.set('acr_values', acrValue)
    url.searchParams.set('code_challenge', codeChallenge)
    url.searchParams.set('code_challenge_method', 'S256')
    window.location.assign(url.toString())
  }

  /**
   * The same redirect for an authenticated session (step-up to loa2). Keycloak's Condition-LoA
   * subflow decides whether that needs a fresh prompt or the SSO cookie covers it.
   */
  function redirectToStepUp() {
    return redirectToLogin('2')
  }


  /**
   * Web-Kanal-Selbstbedienung "Anmeldeverfahren verwalten" (docs/05-api.md): the same `/auth`
   * redirect as `redirectToLogin`, with `kc_action` appended. No `acr_values`: the loa2 gate of
   * MANAGE_AUTH_METHODS lives in the orchestrator's journey. With a valid SSO session no login form
   * appears; otherwise the login runs first, then this action.
   */
  async function redirectToManageMethods() {
    const codeVerifier = randomString(64)
    const codeChallenge = await sha256Base64Url(codeVerifier)
    const uri = redirectUri()
    sessionStorage.setItem(SESSION_STORAGE_KEY, JSON.stringify({ codeVerifier, redirectUri: uri } satisfies StoredVerifier))

    const url = new URL(`${realmBase}/auth`)
    url.searchParams.set('client_id', config.browserClientId)
    url.searchParams.set('redirect_uri', uri)
    url.searchParams.set('response_type', 'code')
    // Keycloak's pages in the language chosen in the demo (LanguageSwitch), not only the browser's.
    url.searchParams.set('ui_locales', language())
    url.searchParams.set('scope', 'openid')
    url.searchParams.set('kc_action', 'orchestrator-manage-methods')
    url.searchParams.set('code_challenge', codeChallenge)
    url.searchParams.set('code_challenge_method', 'S256')
    window.location.assign(url.toString())
  }

  /**
   * If the current URL carries a fresh `?code=...` from the redirect above, exchanges it for tokens
   * and scrubs the query string; `?error=...` (e.g. the user cancelled at Keycloak) is scrubbed too
   * and thrown as [LoginNotCompletedError]; otherwise a no-op. Call once, on mount.
   */
  async function completeLoginIfRedirected(): Promise<TokenSet | null> {
    const params = new URLSearchParams(window.location.search)
    const error = params.get('error')
    if (error) {
      sessionStorage.removeItem(SESSION_STORAGE_KEY)
      window.history.replaceState(null, '', window.location.pathname + window.location.hash)
      throw new LoginNotCompletedError(error, params.get('error_description'))
    }
    const code = params.get('code')
    if (!code) return null

    const storedRaw = sessionStorage.getItem(SESSION_STORAGE_KEY)
    if (!storedRaw) throw new Error(t('Kein PKCE {parameter} gefunden - Login bitte erneut starten.', { parameter: 'code_verifier' }))
    const stored = JSON.parse(storedRaw) as StoredVerifier
    sessionStorage.removeItem(SESSION_STORAGE_KEY)

    const tokens = await exchangeToken({
      grant_type: 'authorization_code',
      code,
      redirect_uri: stored.redirectUri,
      code_verifier: stored.codeVerifier,
    })
    // Scrubs code/session_state/iss from the address bar: a reload must not redeem the used code again.
    window.history.replaceState(null, '', window.location.pathname + window.location.hash)
    return tokens
  }

  function refreshTokens(refreshToken: string): Promise<TokenSet> {
    return exchangeToken({ grant_type: 'refresh_token', refresh_token: refreshToken })
  }

  /**
   * Ends the real Keycloak session, not just this tab's tokens. The Web channel's logout belongs to
   * Keycloak (docs/07-betrieb.md Abschnitt 3), never to the orchestrator.
   */
  function redirectToLogout(idToken: string | undefined) {
    const url = new URL(`${realmBase}/logout`)
    if (idToken) url.searchParams.set('id_token_hint', idToken)
    url.searchParams.set('post_logout_redirect_uri', redirectUri())
    url.searchParams.set('client_id', config.browserClientId)
    window.location.assign(url.toString())
  }

  async function exchangeToken(params: Record<string, string>): Promise<TokenSet> {
    const body = new URLSearchParams({ client_id: config.browserClientId, ...params })
    const response = await fetch(`${realmBase}/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    })
    const json = await response.json()
    if (!response.ok) {
      if (params.grant_type === 'refresh_token' && json.error === 'invalid_grant') throw new SessionEndedError()
      throw new Error(json.error_description ?? json.error ?? t('Token-Endpoint antwortete mit {status}', { status: response.status }))
    }
    return {
      accessToken: json.access_token,
      idToken: json.id_token,
      refreshToken: json.refresh_token,
      expiresAt: Date.now() + Number(json.expires_in ?? 60) * 1000,
    }
  }

  return {
    redirectToLogin,
    redirectToStepUp,
    redirectToManageMethods,
    completeLoginIfRedirected,
    refreshTokens,
    redirectToLogout,
  }
}
