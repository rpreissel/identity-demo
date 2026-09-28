import { t } from '../texts'
import { Tx } from '../Tx'
import { useEffect, useState } from 'react'
import { ApiError, fetchLoa1Login, setLoa1Login, type Loa1Login } from '../api.ts'

/**
 * Switches what the web channel's first sign-in page asks for: Keycloak's password or the
 * orchestrator's method selection (docs/adr/ADR-042-loa1-anmeldung-umschalten.md), realm-wide and
 * at once. Built like AdminLoginThemeView; a 404 means "no Keycloak here".
 */
export function AdminLoa1LoginView() {
  const [login, setLogin] = useState<Loa1Login | null>(null)
  const [unavailable, setUnavailable] = useState(false)
  const [error, setError] = useState('')
  const [switching, setSwitching] = useState(false)

  function reload() {
    fetchLoa1Login()
      .then((state) => setLogin(state.login))
      .catch((err) => {
        if (err instanceof ApiError && err.status === 404) setUnavailable(true)
        else setError(err instanceof Error ? err.message : String(err))
      })
  }

  useEffect(reload, [])

  async function toggle() {
    if (login === null) return
    setSwitching(true)
    try {
      setError('')
      await setLoa1Login(login === 'ORCHESTRATOR' ? 'KEYCLOAK_PASSWORD' : 'ORCHESTRATOR')
      reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setSwitching(false)
    }
  }

  return (
    <div className="card">
      <h2>{t('Beginn der Anmeldung im Web-Kanal')}</h2>
      <p>
        {t(
          'Was Keycloak auf der ersten Anmeldeseite abfragt: sein eigenes Passwortformular oder gleich alle Verfahren des Orchestrators, ' +
            'auch die Anmeldung per QR-Code. Gilt sofort für alle Besucher.',
        )}
      </p>
      {unavailable ? (
        <p className="hint">
          <Tx text="Nur verfügbar, wenn das Backend mit dem Spring-Profil {profil} läuft." profil={'"keycloak"'} />
        </p>
      ) : (
        <>
          {error && <p className="error-card">{error}</p>}
          {login === null ? (
            !error && <p>{t('Lädt…')}</p>
          ) : (
            <ul className="status-list">
              <li>
                <span className="label">{t('Erste Anmeldeseite')}</span>
                <span className="value-with-action">
                  <span className="value">{loa1LoginLabel(login)}</span>
                  <button className="secondary small" onClick={toggle} disabled={switching}>
                    {t('Umschalten')}
                  </button>
                </span>
              </li>
            </ul>
          )}
        </>
      )}
    </div>
  )
}

/** The wording of the website's demo column (WebChannelView). */
function loa1LoginLabel(login: Loa1Login): string {
  return login === 'ORCHESTRATOR' ? t('Gleich alle Verfahren zur Wahl') : t('Erst das Passwort')
}
