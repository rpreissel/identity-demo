import { t } from '../texts'
import { metaFor } from '../tools/registry'

interface SelectMethodViewProps {
  /** The rows in order: the backend's options and, in their place, the methods in [setUp]. */
  options: string[]
  /** Shown in their row but not selectable: what the account or this device already has. */
  setUp?: string[]
  title: string
  description?: string
  onSelect: (toolId: string) => void
}

/**
 * Generic selection page for `type=flow` steps with `stepData.options` (docs/10-frontend.md #2).
 * Entries are complete toolId values; the client picks one and never builds one. `title` and
 * `description` come from the backend (docs/05-api.md): the same `context`/`step` address serves
 * intents that ask different things (log in vs. confirm an account deletion).
 */
export function SelectMethodView({ options, setUp = [], title, description, onSelect }: SelectMethodViewProps) {
  return (
    <div className="card">
      <h2>{title}</h2>
      {description && <p>{description}</p>}
      <ul className="method-choice-list">
        {options.map((toolId) => {
          const meta = metaFor(toolId)
          const done = setUp.includes(toolId)
          const content = (
            <>
              <span className="method-choice-icon" aria-hidden="true">
                {meta.icon}
              </span>
              <span className="method-choice-text">
                <span className="method-choice-label">{meta.label}</span>
                {(done || meta.hint) && <span className="method-choice-hint">{done ? t('Bereits eingerichtet') : meta.hint}</span>}
              </span>
            </>
          )
          return (
            <li key={toolId}>
              {done ? (
                <div className="method-choice method-choice--done">{content}</div>
              ) : (
                <button className="method-choice" onClick={() => onSelect(toolId)}>
                  {content}
                </button>
              )}
            </li>
          )
        })}
      </ul>
    </div>
  )
}
