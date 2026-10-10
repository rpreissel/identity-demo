import { resolveText, t } from '../texts'
import { ErrorCard } from './ErrorCard'
import { errorMessage } from '../errorMessage'
import { useEffect, useState, type ReactNode } from 'react'
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
      .catch((err) => setError(errorMessage(err)))
  }

  useEffect(reload, [])

  async function run(action: () => Promise<void>) {
    try {
      setError('')
      await action()
      reload()
    } catch (err) {
      setError(errorMessage(err))
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
    <section className="card admin-card admin-tools">
      <div className="admin-card-head">
        <div>
          <h2>{t('Verfahren')}</h2>
          <p>
            {mode === 'order'
              ? t('Jede Auswahl (anmelden, identifizieren, einrichten) zeigt ihre Verfahren in dieser Reihenfolge. Die Änderungen gelten erst nach „Speichern“.')
              : t('Was ein Zugang anbietet. Gesperrt wird je Fassung (@1, @2); jeder Client zeigt außerdem nur, was er darstellen kann.')}
          </p>
        </div>
        {channels !== null && (
          <div className="admin-card-tools">
            <div className="segmented" role="tablist" aria-label={t('Zugang')}>
              {channels.map((c) => (
                <button
                  key={c.channel}
                  role="tab"
                  aria-selected={c.channel === channel}
                  className={c.channel === channel ? 'on' : ''}
                  // While a draft is open, the other channel waits: switching would drop it unseen.
                  disabled={mode === 'order' && c.channel !== channel}
                  onClick={() => setChannel(c.channel)}
                >
                  {CHANNEL_LABELS[c.channel]}
                </button>
              ))}
            </div>
            {mode === 'order' ? (
              <>
                <button className="secondary" onClick={leaveOrder}>
                  {t('Abbrechen')}
                </button>
                <button disabled={!changed} onClick={saveOrder}>
                  {t('Speichern')}
                </button>
              </>
            ) : (
              <button className="secondary" onClick={editOrder}>
                {t('Reihenfolge ändern')}
              </button>
            )}
          </div>
        )}
      </div>
      {error && <ErrorCard>{error}</ErrorCard>}
      {channels === null
        ? !error && <p>{t('Lädt…')}</p>
        : current &&
          (mode === 'order' && draft ? (
            <OrderPanel tools={draft.flatMap((id) => current.tools.filter((tool) => tool.toolId === id))} order={draft} onChange={setDraft} />
          ) : (
            <AvailabilityPanel key={channel} channel={current} run={run} />
          ))}
    </section>
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

/** The tool's name from the catalog with its version right behind it, the id below; the id alone when the catalog does not know it. */
function ToolName({ tool, version }: { tool: ToolAvailabilityEntry; version?: number }) {
  const entry = catalogEntry(tool.toolId)
  return (
    <span className="tool-admin-name">
      <span>
        {entry ? resolveText(entry.name) : tool.toolId}
        {version !== undefined && (
          <>
            {'\u00a0'}
            <VersionBadge version={version} />
          </>
        )}
      </span>
      {entry && <code>{tool.toolId}</code>}
    </span>
  )
}

function VersionBadge({ version }: { version: number }) {
  return <span className="version-badge">@{version}</span>
}

/**
 * Which versions this channel offers, as one table: a group row per selection list, a row per
 * version with its switch. A tool with several versions names itself once for all its rows.
 * Switching a version off asks for the reason in place.
 */
function AvailabilityPanel({ channel, run }: { channel: ChannelToolAvailability; run: (a: () => Promise<void>) => void }) {
  const [locking, setLocking] = useState<string | null>(null)
  const [reason, setReason] = useState(() => t('manuell gesperrt'))

  function lock(version: ToolVersionAvailability) {
    run(async () => {
      await setToolAvailability(version.tool, channel.channel, false, reason || undefined)
      setLocking(null)
    })
  }

  function rows(tool: ToolAvailabilityEntry): ReactNode[] {
    const multi = tool.versions.length > 1
    return tool.versions.flatMap((version, index) => {
      const row = (
        <tr key={version.tool} className={version.enabled ? undefined : 'tool-off'}>
          {index === 0 && (
            <td rowSpan={tool.versions.length} className={multi ? 'tool-name tool-name-multi' : 'tool-name'}>
              <ToolName tool={tool} />
              {multi && <span className="tool-count">{t('{anzahl} Fassungen', { anzahl: tool.versions.length })}</span>}
            </td>
          )}
          <td>
            <VersionBadge version={version.version} />
          </td>
          <td>
            <button
              className={version.enabled ? 'tool-switch on' : 'tool-switch'}
              aria-pressed={version.enabled}
              aria-label={version.enabled ? t('{tool} sperren', { tool: version.tool }) : t('{tool} freigeben', { tool: version.tool })}
              disabled={locking === version.tool}
              onClick={() => (version.enabled ? setLocking(version.tool) : run(() => setToolAvailability(version.tool, channel.channel, true)))}
            >
              <span className="tool-switch-track" aria-hidden="true" />
              {version.enabled ? t('An') : t('Aus')}
            </button>
          </td>
          <td className="tool-note">{!version.enabled && (version.reason ?? t('gesperrt'))}</td>
        </tr>
      )
      if (locking !== version.tool) return [row]
      return [
        row,
        <tr key={`${version.tool}-lock`} className="tool-lock-row">
          <td colSpan={multi ? 3 : 4}>
            <div className="tool-admin-lock">
              <label htmlFor={`lock-${version.tool}`}>{t('Grund der Sperre')}</label>
              <input
                id={`lock-${version.tool}`}
                aria-label={t('Grund für die Sperre von {tool}', { tool: version.tool })}
                value={reason}
                onChange={(e) => setReason(e.target.value)}
                autoFocus
              />
              <button className="secondary small" onClick={() => setLocking(null)}>
                {t('Abbrechen')}
              </button>
              <button className="small" onClick={() => lock(version)}>
                {t('Sperren')}
              </button>
            </div>
          </td>
        </tr>,
      ]
    })
  }

  return (
    <div className="tool-table-wrap">
      <table className="tool-table">
        <thead>
          <tr>
            <th>{t('Verfahren')}</th>
            <th>{t('Fassung')}</th>
            <th>{t('Angeboten')}</th>
            <th>{t('Hinweis')}</th>
          </tr>
        </thead>
        {byRole(channel.tools).map(([role, tools]) => (
          <tbody key={role}>
            <tr className="tool-group">
              <th colSpan={4} scope="colgroup">
                {ROLE_LABELS[role]}
              </th>
            </tr>
            {tools.flatMap(rows)}
          </tbody>
        ))}
      </table>
    </div>
  )
}

/**
 * The draft of the order each selection list shows. A locked tool stays in it, greyed out, so it is
 * in the right place once it is freed. ▲/▼ swap with the neighbour in the draft only.
 */
function OrderPanel({ tools, order, onChange }: { tools: ToolAvailabilityEntry[]; order: string[]; onChange: (order: string[]) => void }) {

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
        {byRole(tools)
          .filter(([, group]) => group.length > 1)
          .map(([role, group]) => (
            <section key={role} className="tool-admin-group">
              <h4>{ROLE_LABELS[role]}</h4>
              <ol className="tool-admin-order">
                {group.map((tool, index) => (
                  <li key={tool.toolId} className={tool.versions.some((v) => v.enabled) ? '' : 'tool-admin-off'}>
                    <span className="tool-admin-rank">{index + 1}</span>
                    <ToolName tool={tool} />
                    {!tool.versions.some((v) => v.enabled) && <span className="tool-admin-tag">{t('gesperrt')}</span>}
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
