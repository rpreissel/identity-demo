import { t } from '../texts'
import { Tx } from '../Tx'
/**
 * Swagger UI isn't proxied by the vite dev server (only /orchestrator and /tools/api are,
 * vite.config.ts): in dev it lives on the backend's port, in a same-origin deployment on
 * window.location.origin.
 */
const BACKEND_ORIGIN = window.location.port === '5173' ? 'http://localhost:8080' : window.location.origin

/**
 * Matches src/main/resources/application.yml. The H2 console has no reliable query-param prefill,
 * so these are shown for copying.
 */
const H2_JDBC_URL = 'jdbc:h2:file:./data/identitydb'
const H2_USER = 'sa'

/**
 * Links into the one orchestrator backend behind both channels, part of the server status. The
 * API description exists only in demo mode (`springdoc` in application.yml), so its link does too.
 */
export function DeveloperTools({ demoMode }: { demoMode: boolean }) {
  return (
    <>
      <h3>{t('Entwickler-Werkzeuge')}</h3>
      <ul className="status-list">
        {demoMode && (
          <li>
            <span className="label">{t('API-Doku')}</span>
            <a className="value" href={`${BACKEND_ORIGIN}/swagger-ui/index.html`} target="_blank" rel="noreferrer">
              Swagger/OpenAPI UI
            </a>
          </li>
        )}
        <li>
          <span className="label">{t('H2-Konsole')}</span>
          <span className="value" title={t('JDBC URL: {url}\nUser: {user}\nPassword: (leer)', { url: H2_JDBC_URL, user: H2_USER })}>
            <a href={`${BACKEND_ORIGIN}/h2-console`} target="_blank" rel="noreferrer">
              {t('öffnen')}
            </a>{' '}
            <Tx text="({url}, User {user}, kein Passwort)" url={H2_JDBC_URL} user={H2_USER} />{' '}
            {t('nur vom Rechner, auf dem der Server läuft')}
          </span>
        </li>
      </ul>
    </>
  )
}
