import { t } from '../texts'
import { useEffect, useRef, useState } from 'react'
import type { DpopKeyPair } from '../dpop.ts'
import { getToken } from '../api.ts'
import type { TokenResponse } from '../types'
import { shorten } from '../format.ts'
import { parseJwtPayload } from '../jwt.ts'
import { Disclosure } from './Disclosure'

interface TokenPanelProps {
  dpop: DpopKeyPair
  channelSessionId: string
  /** After "AccessToken aktualisieren" brought a new token - what the screen shows may have changed with it. */
  onRefreshed?: () => void
}

/**
 * Forces the backend to mint a fresh AccessToken: larger than the backend's ACCESS_TTL, so no
 * valid token qualifies.
 */
const FORCE_REFRESH_MIN_VALIDITY_SECONDS = 10_000

function formatRemaining(expiresAt: string): string {
  const seconds = Math.round((new Date(expiresAt).getTime() - Date.now()) / 1000)
  if (seconds <= 0) return t('abgelaufen')
  if (seconds < 120) return t('{sekunden}s', { sekunden: seconds })
  return t('{minuten}min', { minuten: Math.round(seconds / 60) })
}

/**
 * App-Kanal AccessToken, vom Orchestrator geholt (docs/05-api.md #3a): je nach Backend-Profil ein
 * Mock-JWT oder ein echtes Keycloak-Token, diese Ansicht unterscheidet sie nicht. Loads its own
 * data. The backend decides on every getToken() whether to mint a new token; this panel never
 * sees a RefreshToken value, only its expiry.
 */
export function TokenPanel({ dpop, channelSessionId, onRefreshed }: TokenPanelProps) {
  const [token, setToken] = useState<TokenResponse | null>(null)
  const [error, setError] = useState('')

  // Guards the mount-time load against StrictMode's double invocation: a concurrent second
  // getToken() could fail with CONCURRENT_MODIFICATION. The buttons call loadToken() directly.
  const loadingInitialTokenRef = useRef(false)

  function loadToken(minValiditySeconds?: number, onSettled?: () => void, onLoaded?: () => void) {
    getToken(dpop, channelSessionId, minValiditySeconds)
      .then((loaded) => {
        setToken(loaded)
        onLoaded?.()
      })
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
      .finally(onSettled)
  }

  useEffect(() => {
    if (loadingInitialTokenRef.current) return
    loadingInitialTokenRef.current = true
    loadToken(undefined, () => {
      loadingInitialTokenRef.current = false
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dpop, channelSessionId])

  const payload = token ? parseJwtPayload(token.accessToken) : null

  return (
    <div className="card token-panel">
      <h3 className="section-heading">AccessToken</h3>
      {error && <div className="hint">{error}</div>}
      {token && (
        <ul className="status-list">
          <li>
            <span className="label">{t('Gültig noch')}</span>
            <span className="value value-plain">{formatRemaining(token.accessExpiresAt)}</span>
          </li>
          <li>
            <span className="label">{t('RefreshToken gültig noch')}</span>
            <span className="value value-plain">{formatRemaining(token.refreshExpiresAt)}</span>
          </li>
        </ul>
      )}
      <div className="form-actions">
        <button className="secondary" onClick={() => loadToken(FORCE_REFRESH_MIN_VALIDITY_SECONDS, undefined, onRefreshed)}>
          {t('AccessToken aktualisieren')}
        </button>
      </div>
      {token && (
        <Disclosure summary={t('Technische Details (Token, Claims)')}>
          <ul className="status-list">
            <li>
              <span className="label">AccessToken</span>
              <span className="value" title={token.accessToken}>{shorten(token.accessToken, 12, 8)}</span>
            </li>
            <li>
              <span className="label">{t('Typ')}</span>
              <span className="value">{token.tokenType}</span>
            </li>
          </ul>
          {payload && (
            <>
              <h4>{t('Geparste AccessToken-Claims')}</h4>
              <ul className="status-list">
                {Object.entries(payload).map(([key, value]) => (
                  <li key={key}>
                    <span className="label">{key}</span>
                    <span className="value">{Array.isArray(value) ? value.join(', ') : String(value)}</span>
                  </li>
                ))}
              </ul>
            </>
          )}
        </Disclosure>
      )}
    </div>
  )
}
