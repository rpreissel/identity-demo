import { resolveText, t } from '../texts'
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
import { catalogEntry } from '../toolCatalog'

const CHANNEL_LABELS: Record<ChannelType, string> = {
  APP: t('App'),
  WEB: t('Website'),
}
const CHANNEL_ICONS: Record<ChannelType, string> = { APP: '📱', WEB: '🌐' }

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

type Mode = 'availability' | 'order'

/**
 * The operator's say over tools, per channel type (docs/03-tool-architektur.md, availability).
 * One channel at a time (tabs App / Website). The page switches single versions of a tool on and
 * off for this channel (ADR-51) at once. "Reihenfolge ändern" opens a draft of the order its
 * selection lists show: the arrows move tools in the draft only, "Speichern" writes it, "Abbrechen"
 * drops it. Both act on the very next step of any journey. Behind the admin login like every
 * operator endpoint.
 */
export function AdminToolAvailabilityView() {
  const [channels, setChannels] = useState<ChannelToolAvailability[] | null>(null)
  const [channel, setChannel] = useState<ChannelType>('APP')
  const [mode, setMode] = useState<Mode>('availability')
  // The order being edited (toolIds of the current channel); null outside the order view.
  const [draft, setDraft] = useState<string[] | null>(null)
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

  const current = channels?.find((c) => c.channel === channel)
  const saved = current?.tools.map((tool) => tool.toolId) ?? []
  const changed = draft !== null && draft.join() !== saved.join()

  function editOrder() {
    setDraft(saved)
    setMode('order')
  }

  function leaveOrder() {
    setDraft(null)
    setMode('availability')
  }

  function saveOrder() {
    if (!draft) return
    run(async () => {
      await setToolOrder(channel, draft)
      leaveOrder()
    })
  }

  return (
    <div className="card">
      <h2>{t('Verfahren je Kanal')}</h2>
      <p>{t('Was jeder Zugang anbietet und in welcher Reihenfolge. Gespeichert wirkt jede Änderung sofort, auch in laufenden Vorgängen.')}</p>
      {error && <p className="error-card">{error}</p>}
      {channels === null ? (
        !error && <p>{t('Lädt…')}</p>
      ) : (
        <>
          <div className="app-tabs tool-admin-tabs" role="tablist" aria-label={t('Zugang')}>
            {channels.map((c) => (
              <button
                key={c.channel}
                role="tab"
                aria-selected={c.channel === channel}
                className={c.channel === channel ? 'active' : ''}
                // While a draft is open, the other channel waits: switching would drop it unseen.
                disabled={mode === 'order' && c.channel !== channel}
                onClick={() => setChannel(c.channel)}
              >
                {CHANNEL_ICONS[c.channel]} {CHANNEL_LABELS[c.channel]}
              </button>
            ))}
          </div>
          <div className="tool-admin-toolbar">
            <p className="tool-admin-intro">
              {mode === 'order'
                ? t('Jede Auswahl (anmelden, identifizieren, einrichten) zeigt ihre Verfahren in dieser Reihenfolge. Die Änderungen gelten erst nach „Speichern“; gesperrte Verfahren sind hier ausgeblendet.')
                : t('Gesperrt wird je Fassung (@1, @2). Wer eine gesperrte Fassung spricht, bekommt das Verfahren nicht angeboten; jeder Client zeigt außerdem nur, was er darstellen kann.')}
            </p>
            {mode === 'order' ? (
              <span className="tool-admin-actions">
                <button className="secondary" onClick={leaveOrder}>
                  {t('Abbrechen')}
                </button>
                <button disabled={!changed} onClick={saveOrder}>
                  {t('Speichern')}
                </button>
              </span>
            ) : (
              <button className="secondary" onClick={editOrder}>
                <span aria-hidden="true">⇅</span> {t('Reihenfolge ändern')}
              </button>
            )}
          </div>
          {current &&
            (mode === 'order' && draft ? (
              <OrderPanel tools={draft.flatMap((id) => current.tools.filter((tool) => tool.toolId === id))} order={draft} onChange={setDraft} />
            ) : (
              <AvailabilityPanel key={channel} channel={current} run={run} />
            ))}
        </>
      )}
    </div>
  )
}

/** The tools in the order the server lists them (it sorts by role): one group per selection list. */
function byRole(tools: ToolAvailabilityEntry[]): [ToolRole, ToolAvailabilityEntry[]][] {
  const groups: [ToolRole, ToolAvailabilityEntry[]][] = []
  for (const tool of tools) {
    const last = groups.at(-1)
    if (last && last[0] === tool.role) last[1].push(tool)
    else groups.push([tool.role, [tool]])
  }
  return groups
}

/** The tool's name from the catalog, its id beside it; the id alone when the catalog does not know it. */
function ToolName({ tool }: { tool: ToolAvailabilityEntry }) {
  const entry = catalogEntry(tool.toolId)
  return (
    <span className="tool-admin-name">
      <span>{entry ? resolveText(entry.name) : tool.toolId}</span>
      {entry && <code>{tool.toolId}</code>}
    </span>
  )
}

/** Which versions this channel offers: one switch per version; locking asks for a reason in place. */
function AvailabilityPanel({ channel, run }: { channel: ChannelToolAvailability; run: (a: () => Promise<void>) => void }) {
  const [locking, setLocking] = useState<string | null>(null)
  const [reason, setReason] = useState(() => t('manuell gesperrt'))

  function lock(version: ToolVersionAvailability) {
    run(async () => {
      await setToolAvailability(version.tool, channel.channel, false, reason || undefined)
      setLocking(null)
    })
  }

  return (
    <div className="tool-admin-panel">
      <div className="tool-admin-grid">
        {byRole(channel.tools).map(([role, tools]) => (
          <section key={role} className="tool-admin-group">
            <h4>{ROLE_LABELS[role]}</h4>
            <ul className="tool-admin-rows">
              {tools.map((tool) => {
                const lockedVersions = tool.versions.filter((v) => !v.enabled)
                const editing = tool.versions.find((v) => v.tool === locking)
                return (
                  <li key={tool.toolId} className={lockedVersions.length === tool.versions.length ? 'tool-admin-off' : ''}>
                    <div className="tool-admin-row">
                      <ToolName tool={tool} />
                      <span className="tool-admin-versions">
                        {tool.versions.map((version) => (
                          <button
                            key={version.tool}
                            className={`version-switch ${version.enabled ? 'on' : 'off'}`}
                            aria-pressed={version.enabled}
                            aria-label={version.enabled ? t('{tool} sperren', { tool: version.tool }) : t('{tool} freigeben', { tool: version.tool })}
                            title={version.enabled ? t('Freigegeben - klicken zum Sperren') : t('Gesperrt - klicken zum Freigeben')}
                            onClick={() => (version.enabled ? setLocking(version.tool) : run(() => setToolAvailability(version.tool, channel.channel, true)))}
                          >
                            <span aria-hidden="true">{version.enabled ? '●' : '○'}</span> @{version.version}
                          </button>
                        ))}
                      </span>
                    </div>
                    {lockedVersions.map((version) => (
                      <p key={version.tool} className="tool-admin-reason">
                        @{version.version}: {version.reason ? t('gesperrt: {grund}', { grund: version.reason }) : t('gesperrt')}
                      </p>
                    ))}
                    {editing && (
                      <div className="tool-admin-lock">
                        <input
                          aria-label={t('Grund für die Sperre von {tool}', {
                            tool: editing.tool,
                          })}
                          value={reason}
                          onChange={(e) => setReason(e.target.value)}
                          autoFocus
                        />
                        <button className="small" onClick={() => lock(editing)}>
                          {t('@{fassung} sperren', {
                            fassung: editing.version,
                          })}
                        </button>
                        <button className="secondary small" onClick={() => setLocking(null)}>
                          {t('Abbrechen')}
                        </button>
                      </div>
                    )}
                  </li>
                )
              })}
            </ul>
          </section>
        ))}
      </div>
    </div>
  )
}

/**
 * The draft of the order each selection list shows, among the tools it can offer: a fully locked
 * tool is left out and keeps its place. ▲/▼ swap with the neighbour in the draft only.
 */
function OrderPanel({ tools, order, onChange }: { tools: ToolAvailabilityEntry[]; order: string[]; onChange: (order: string[]) => void }) {
  const offered = tools.filter((tool) => tool.versions.some((v) => v.enabled))

  function move(group: ToolAvailabilityEntry[], index: number, by: -1 | 1) {
    const next = [...order]
    const a = next.indexOf(group[index].toolId)
    const b = next.indexOf(group[index + by].toolId)
    ;[next[a], next[b]] = [next[b], next[a]]
    onChange(next)
  }

  return (
    <div className="tool-admin-panel">
      <div className="tool-admin-grid">
        {/* A list with one tool has no order to set. */}
        {byRole(offered)
          .filter(([, group]) => group.length > 1)
          .map(([role, group]) => (
            <section key={role} className="tool-admin-group">
              <h4>{ROLE_LABELS[role]}</h4>
              <ol className="tool-admin-order">
                {group.map((tool, index) => (
                  <li key={tool.toolId}>
                    <span className="tool-admin-rank">{index + 1}</span>
                    <ToolName tool={tool} />
                    <span className="tool-admin-arrows">
                      <button
                        className="secondary small"
                        aria-label={t('{tool} nach oben', {
                          tool: tool.toolId,
                        })}
                        disabled={index === 0}
                        onClick={() => move(group, index, -1)}
                      >
                        ▲
                      </button>
                      <button
                        className="secondary small"
                        aria-label={t('{tool} nach unten', {
                          tool: tool.toolId,
                        })}
                        disabled={index === group.length - 1}
                        onClick={() => move(group, index, 1)}
                      >
                        ▼
                      </button>
                    </span>
                  </li>
                ))}
              </ol>
            </section>
          ))}
      </div>
    </div>
  )
}
