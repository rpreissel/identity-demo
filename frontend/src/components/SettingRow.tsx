import type { ReactNode } from 'react'

export interface SettingChoice<V extends string> {
  value: V
  label: string
}

interface Props<V extends string> {
  title: string
  hint: ReactNode
  choices: readonly [SettingChoice<V>, SettingChoice<V>]
  /** The current choice; null while loading or when the setting is not available (then `children` explains). */
  value: V | null
  disabled?: boolean
  onChange: (value: V) => void
  children?: ReactNode
}

/** One switch of the admin page's "Allgemein" block: what it sets on the left, the two choices on the right. */
export function SettingRow<V extends string>({ title, hint, choices, value, disabled, onChange, children }: Props<V>) {
  return (
    <div className="setting-row">
      <div className="setting-text">
        <strong>{title}</strong>
        <span>{hint}</span>
        {children}
      </div>
      {value !== null && (
        <div className="segmented" role="radiogroup" aria-label={title}>
          {choices.map((choice) => (
            <button
              key={choice.value}
              role="radio"
              aria-checked={choice.value === value}
              className={choice.value === value ? 'on' : ''}
              disabled={disabled}
              onClick={() => choice.value !== value && onChange(choice.value)}
            >
              {choice.label}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
