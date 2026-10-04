import { t } from '../texts'
import { useEffect, useState } from 'react'
import { fetchServerInfo, type ChannelType, type ServerInfo } from '../api.ts'
import { knownToolIds, toolVersionOf } from '../tools/registry'

interface UnavailableToolsProps {
  /** Whose operator locks count - a lock for the Web channel does not affect the App, and vice versa. */
  channel: ChannelType
  /**
   * The client's own declared availability, where this page knows it (the App channel's
   * "Erweitert"). The Web channel's declaration lives in the Keycloak extension, so there only the
   * operator locks are shown.
   */
  availableTools?: string[]
}

/**
 * Both availability axes only show up as an absence in a candidate list (docs/05-api.md): a tool
 * that's off never appears in `stepData.options`. This says so, as a short list. It stands where
 * a reader has already opened a section for it, so it is not collapsed once more. The
 * operator locks come from the public server status, since a channel user has no admin login.
 */
export function UnavailableTools({ channel, availableTools }: UnavailableToolsProps) {
  const [locks, setLocks] = useState<ServerInfo['disabledTools']>([])

  useEffect(() => {
    fetchServerInfo()
      .then((info) => setLocks(info.disabledTools.filter((d) => d.channel === channel)))
      .catch(() => setLocks([]))
  }, [channel])

  // A lock holds for one version (`enroll-sms@2`, ADR-51). With availableTools this page is the
  // App, which speaks one version per tool; otherwise every locked version is listed.
  const candidates = availableTools ? knownToolIds.map((toolId) => `${toolId}@${toolVersionOf(toolId)}`) : locks.map((l) => l.tool)
  const rows = candidates
    .map((tool) => {
      const toolId = tool.split('@')[0]
      const clientDisabled = availableTools ? !availableTools.includes(toolId) : false
      const lock = locks.find((e) => e.tool === tool)
      return { toolId: availableTools ? toolId : tool, clientDisabled, lock }
    })
    .filter((row) => row.clientDisabled || row.lock)

  if (rows.length === 0) return null

  return (
    <div className="unavailable-tools">
      <p>{t('{anzahl} Verfahren nicht verfügbar', { anzahl: rows.length })}</p>
      <ul>
        {rows.map((row) => (
          <li key={row.toolId}>
            <code>{row.toolId}</code>{' '}
            {[
              row.clientDisabled && t('auf diesem Client deaktiviert'),
              row.lock && (row.lock.reason ? t('gesperrt: {grund}', { grund: row.lock.reason }) : t('gesperrt')),
            ]
              .filter(Boolean)
              .join(' · ')}
          </li>
        ))}
      </ul>
    </div>
  )
}
