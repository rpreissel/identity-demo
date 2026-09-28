import type { ActiveChannel, ActiveSessionsReport, ChannelStateName, ChannelType, KeycloakSession } from '../api'
import { shorten } from '../format'
import { language, t } from '../texts'

/**
 * Who is using the demo right now (docs/10-frontend.md #0): the orchestrator's live channels and
 * Keycloak's open sessions. Shown on the admin page and before the welcome page's reset; it only
 * renders a report, loading it is the caller's business.
 */
export function ActiveSessionsView({ report }: { report: ActiveSessionsReport }) {
  const { channels, keycloak } = report
  return (
    <div className="active-sessions">
      <ul className="status-list">
        {channels.perType.map((p) => (
          <li key={p.channel}>
            <span className="label">{channelLabel(p.channel)}</span>
            <span className="value">{t('{anzahl} aktiv', { anzahl: p.count })}</span>
          </li>
        ))}
      </ul>

      {channels.newest.length > 0 && (
        <>
          <h3>{t('Die neuesten Sitzungen')}</h3>
          <ul className="task-list">
            {channels.newest.map((c) => (
              <ChannelRow key={c.channelSessionId} channel={c} />
            ))}
          </ul>
        </>
      )}

      {keycloak && (
        <>
          <h3>{t('Sitzungen in Keycloak')}</h3>
          {keycloak.error ? (
            <p className="hint">{t('Keycloak ist nicht erreichbar: {grund}', { grund: keycloak.error })}</p>
          ) : (
            keycloak.clients.map((client) => (
              <section key={client.client} className="active-sessions-client">
                <h4>
                  {client.client === 'WEBSITE' ? t('Website') : t('App')} · {t('{anzahl} offen', { anzahl: client.count })}
                </h4>
                {client.newest.length > 0 && (
                  <ul className="task-list">
                    {client.newest.map((s) => (
                      <KeycloakRow key={s.sessionId} session={s} />
                    ))}
                  </ul>
                )}
              </section>
            ))
          )}
        </>
      )}
    </div>
  )
}

function ChannelRow({ channel }: { channel: ActiveChannel }) {
  return (
    <li className="task-row">
      <span>
        {channelLabel(channel.channel)} · {who(channel.displayName, channel.accountId)}
        <span className="task-note"> · {stateLabel(channel.state)}</span>
      </span>
      <span className="task-note">
        {t('seit {beginn}, zuletzt aktiv {zuletzt}', { beginn: time(channel.createdAt), zuletzt: time(channel.lastAccessedAt) })}
      </span>
    </li>
  )
}

function KeycloakRow({ session }: { session: KeycloakSession }) {
  return (
    <li className="task-row">
      <span>
        {who(session.displayName ?? session.username, session.accountId)}
        {session.channelSessionId && (
          <span className="task-note">
            {' · '}
            {t('Sitzung {id}', { id: shorten(session.channelSessionId) })}
            {session.channelState ? `, ${stateLabel(session.channelState)}` : ''}
          </span>
        )}
      </span>
      <span className="task-note">
        {t('seit {beginn}, zuletzt aktiv {zuletzt}', { beginn: time(session.start), zuletzt: time(session.lastAccess) })}
      </span>
    </li>
  )
}

function channelLabel(channel: ChannelType): string {
  return channel === 'APP' ? t('App') : t('Website')
}

function who(name: string | null | undefined, accountId: number | null | undefined): string {
  if (name) return name
  if (accountId != null) return t('Konto {id}', { id: accountId })
  return t('ohne Konto')
}

function stateLabel(state: ChannelStateName): string {
  switch (state) {
    case 'ANONYMOUS': return t('noch nicht angemeldet')
    case 'REGISTERING': return t('registriert sich')
    case 'AUTHENTICATED': return t('angemeldet')
    case 'STEP_UP_REQUIRED': return t('braucht eine zusätzliche Bestätigung')
    case 'STEP_UP_IN_PROGRESS': return t('bestätigt gerade zusätzlich')
    case 'LOGGED_OUT': return t('abgemeldet')
    case 'EXPIRED': return t('abgelaufen')
  }
}

function time(iso: string): string {
  return new Date(iso).toLocaleTimeString(language(), { hour: '2-digit', minute: '2-digit' })
}
