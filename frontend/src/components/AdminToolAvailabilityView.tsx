import { t } from '../texts'
import { useEffect, useState } from 'react'
import {
  fetchToolAvailability,
  setToolAvailability,
  setToolOrder,
  type ChannelToolAvailability,
  type ChannelType,
  type ToolAvailabilityEntry,
  type ToolRole,
  type ToolVersionAvailability,
} from '../api.ts'

const CHANNEL_LABELS: Record<ChannelType, string> = { APP: t('App-Kanal'), WEB: t('Web-Kanal') }

/** One heading per role - each role is one kind of selection list the user sees. */
const ROLE_LABELS: Record<ToolRole, string> = {
  KNOWN_ACCOUNT_AUTH: t('Anmelden - Konto bekannt'),
  ACCOUNT_LOOKUP_AUTH: t('Anmelden - über E-Mail-Adresse'),
  IDENTIFICATION: t('Identifizieren'),
  CORRELATION: t('Person im Personenverzeichnis zuordnen'),
  ENROLLMENT: t('Einrichten'),
  ATTESTATION: t('E-Mail bestätigen'),
  PEER_APPROVAL: t('Web-Login bestätigen'),
}

/**
 * The operator's say over tools, per channel type (docs/03-tool-architektur.md, availability):
 * one list per channel, in the order that channel offers its tools. ▲/▼ move a tool and save at
 * once; the switches lock one version of it for this channel only (ADR-51) - the order is per
 * tool. Both act on the very next step of any journey.
 * Behind the admin login like every operator endpoint.
 */
export function AdminToolAvailabilityView() {
  const [channels, setChannels] = useState<ChannelToolAvailability[] | null>(null)
  const [error, setError] = useState('')

  function reload() {
    fetchToolAvailability()
      .then(setChannels)
      .catch((err) => setError(err instanceof Error ? err.message : String(err)))
  }

  useEffect(reload, [])

  async function run(action: () => Promise<void>) {
    try {
      setError('')
      await action()
      reload()
    } catch (err) {
      setError(err instanceof Error ? err.message : String(err))
    }
  }

  return (
    <div className="card">
      <h2>{t('Verfahren je Kanal')}</h2>
      <p>
        {t(
          'Reihenfolge und Sperre gelten je Kanal und wirken sofort, auch in laufenden Vorgängen: Jede Auswahl (Anmelden, ' +
            'Identifizieren, Einrichten) zeigt ihre Verfahren in dieser Reihenfolge. Gesperrt wird je Fassung eines Verfahrens ' +
            '(@1, @2): Ein Client, der die gesperrte Fassung spricht, bekommt das Verfahren nicht mehr angeboten. Zusätzlich ' +
            'erklärt jeder Client selbst, welche Verfahren er darstellen kann - beide Filter wirken zusammen.',
        )}
      </p>
      {error && <p className="error-card">{error}</p>}
      {channels === null ? (
        !error && <p>{t('Lädt…')}</p>
      ) : (
        <div className="tool-channel-columns">
          {channels.map((c) => (
            <ChannelToolList key={c.channel} channel={c} run={run} />
          ))}
        </div>
      )}
    </div>
  )
}

function ChannelToolList({ channel, run }: { channel: ChannelToolAvailability; run: (a: () => Promise<void>) => void }) {
  // The tool version whose lock reason is being asked for inline (instead of a blocking window.prompt).
  const [locking, setLocking] = useState<string | null>(null)
  const [reason, setReason] = useState(() => t('manuell gesperrt'))
  const ids = channel.tools.map((tool) => tool.toolId)

  // Groups in the order the server lists them (it already sorts by role) - a selection only ever
  // shows tools of one role, so that is where an order means something.
  const groups: [ToolRole, ToolAvailabilityEntry[]][] = []
  for (const tool of channel.tools) {
    const last = groups.at(-1)
    if (last && last[0] === tool.role) last[1].push(tool)
    else groups.push([tool.role, [tool]])
  }

  /** Swaps [tool] with its neighbour in the same role and saves the channel's whole order. */
  function move(group: ToolAvailabilityEntry[], index: number, by: -1 | 1) {
    const order = [...ids]
    const a = order.indexOf(group[index].toolId)
    const b = order.indexOf(group[index + by].toolId)
    ;[order[a], order[b]] = [order[b], order[a]]
    run(() => setToolOrder(channel.channel, order))
  }

  function lock(version: ToolVersionAvailability) {
    run(async () => {
      await setToolAvailability(version.tool, channel.channel, false, reason || undefined)
      setLocking(null)
    })
  }

  return (
    <div>
      <h3>{CHANNEL_LABELS[channel.channel]}</h3>
      {groups.map(([role, group]) => (
        <div key={role} className="tool-order-group">
          <h4>{ROLE_LABELS[role]}</h4>
          <ol className="tool-order-list">
            {group.map((tool, index) => (
              <li key={tool.toolId} className={tool.versions.some((v) => v.enabled) ? '' : 'tool-locked'}>
                <span className="tool-order-arrows">
                  <button
                    className="secondary small"
                    aria-label={t('{tool} nach oben', { tool: tool.toolId })}
                    disabled={index === 0}
                    onClick={() => move(group, index, -1)}
                  >
                    ▲
                  </button>
                  <button
                    className="secondary small"
                    aria-label={t('{tool} nach unten', { tool: tool.toolId })}
                    disabled={index === group.length - 1}
                    onClick={() => move(group, index, 1)}
                  >
                    ▼
                  </button>
                </span>
                <code className="tool-order-name">{tool.toolId}</code>
                <ul className="tool-versions">
                  {tool.versions.map((version) => (
                    <li key={version.tool} className={version.enabled ? '' : 'tool-locked'}>
                      <code>@{version.version}</code>
                      {!version.enabled && (
                        <span className="tool-order-reason">
                          {version.reason ? t('gesperrt: {grund}', { grund: version.reason }) : t('gesperrt')}
                        </span>
                      )}
                      {locking === version.tool ? (
                        <span className="value-with-action">
                          <input
                            aria-label={t('Grund für die Sperre von {tool}', { tool: version.tool })}
                            value={reason}
                            onChange={(e) => setReason(e.target.value)}
                          />
                          <button className="small" onClick={() => lock(version)}>
                            {t('Sperren')}
                          </button>
                          <button className="secondary small" onClick={() => setLocking(null)}>
                            {t('Abbrechen')}
                          </button>
                        </span>
                      ) : (
                        <button
                          className="secondary small"
                          aria-label={version.enabled ? t('{tool} sperren', { tool: version.tool }) : t('{tool} freigeben', { tool: version.tool })}
                          onClick={() =>
                            version.enabled
                              ? setLocking(version.tool)
                              : run(() => setToolAvailability(version.tool, channel.channel, true))
                          }
                        >
                          {version.enabled ? t('Sperren…') : t('Freigeben')}
                        </button>
                      )}
                    </li>
                  ))}
                </ul>
              </li>
            ))}
          </ol>
        </div>
      ))}
    </div>
  )
}
