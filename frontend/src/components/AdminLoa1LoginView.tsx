import { t } from '../texts'
import { Tx } from '../Tx'
import { useEffect, useState } from 'react'
import { ApiError, fetchLoa1Login, setLoa1Login, type Loa1Login } from '../api.ts'
import { SettingRow } from './SettingRow'

/**
 * Switches what the web channel's first sign-in page asks for: Keycloak's password or the
 * orchestrator's method selection (docs/adr/ADR-042-loa1-anmeldung-umschalten.md), realm-wide and
 * at once. A 404 means "no Keycloak here".
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

  async function choose(next: Loa1Login) {
    setSwitching(true)
    try {
      setError('')
      await setLoa1Login(next)
      reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    } finally {
      setSwitching(false)
    }
  }

  return (
    <SettingRow
      title={t('Erste Anmeldeseite der Website')}
      hint={t('Was Keycloak bei „Anmelden“ zuerst zeigt: sein Passwortformular oder gleich alle Verfahren, auch die Anmeldung per QR-Code. Gilt sofort für alle.')}
      choices={[
        { value: 'ORCHESTRATOR', label: t('Gleich alle Verfahren zur Wahl') },
        { value: 'KEYCLOAK_PASSWORD', label: t('Erst das Passwort') },
      ]}
      value={unavailable ? null : login}
      disabled={switching}
      onChange={choose}
    >
      {unavailable && (
        <span className="setting-note">
          <Tx text="Nur verfügbar, wenn das Backend mit dem Spring-Profil {profil} läuft." profil={'"keycloak"'} />
        </span>
      )}
      {error && <span className="error-text">{error}</span>}
    </SettingRow>
  )
}
