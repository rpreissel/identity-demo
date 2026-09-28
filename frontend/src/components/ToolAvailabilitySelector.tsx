import { t } from '../texts'
import { knownToolIds } from '../tools/registry'

interface ToolAvailabilitySelectorProps {
  availableTools: string[]
  onChange: (toolIds: string[]) => void
}

/**
 * Demo stand-in for "the user disabled a method locally" / "this client version doesn't support
 * it yet" (docs/03-tool-architektur.md, availability). Candidates are `knownToolIds`, the toolIds
 * this client has a form for, not the full backend catalog. Same row shape as
 * AdminToolAvailabilityView's list: both make a tool available or not, on different axes.
 */
export function ToolAvailabilitySelector({ availableTools, onChange }: ToolAvailabilitySelectorProps) {
  function toggle(toolId: string) {
    onChange(
      availableTools.includes(toolId) ? availableTools.filter((id) => id !== toolId) : [...availableTools, toolId],
    )
  }

  return (
    <div className="tool-availability-selector">
      <h3>
        {t('Verfügbare Tools auf diesem Client ({anzahl}/{gesamt})', { anzahl: availableTools.length, gesamt: knownToolIds.length })}
      </h3>
      <p>
        {t(
          'Abgewählte Tools bietet die Demo ab der nächsten neu gestarteten Journey nicht mehr an - simuliert eine ältere Client-Version oder eine ' +
            'lokale Nutzer-Einstellung. Unabhängig davon kann das Backend Tools zusätzlich global sperren (siehe ' +
            '"Verfahren je Kanal" auf der Admin-Seite) - beide Sperren wirken zusammen, keine hebt die andere auf.',
        )}
      </p>
      <ul className="status-list">
        {knownToolIds.map((toolId) => {
          const enabled = availableTools.includes(toolId)
          return (
            <li key={toolId}>
              <span className="label">{toolId}</span>
              <span className="value-with-action">
                <span className="value">{enabled ? t('verfügbar') : t('nicht verfügbar')}</span>
                <button className="secondary small" onClick={() => toggle(toolId)}>
                  {enabled ? t('Entfernen') : t('Hinzufügen')}
                </button>
              </span>
            </li>
          )
        })}
      </ul>
    </div>
  )
}
