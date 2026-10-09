import { useContext, useEffect, useState } from 'react'
import { createPortal } from 'react-dom'
import { t } from '../../texts'
import { DemoSlotContext } from '../demoSlot'

type Person = Record<string, string | null | undefined>

/**
 * The demo's test persons (like the App channel's DemoPersonPicker.tsx): picking one fills the
 * given inputs - `fields` maps input id to the person's field - and the first person is taken on
 * load. It renders into the demo aside beside the card (Layout.tsx), outside the form, so the form
 * looks as it would without the demo. The inputs stay free text; without demo values (ADR-28) there
 * is no list and the form starts empty.
 */
export function DemoPersonPicker({
  personsJson,
  fields,
  labelKey,
  title,
}: {
  personsJson?: string | null
  fields: Record<string, string>
  /** An entry field that already holds the whole option text (the invitation picker, ADR-48). */
  labelKey?: string
  /** Replaces "Testperson übernehmen". */
  title?: string
}) {
  const [persons] = useState<Person[]>(() => {
    if (!personsJson || personsJson === 'null') return []
    try {
      return JSON.parse(personsJson) as Person[]
    } catch {
      return []
    }
  })
  const [chosen, setChosen] = useState(persons.length > 0 ? '0' : '')
  const slot = useContext(DemoSlotContext)

  useEffect(() => {
    if (persons.length > 0) apply(persons[0], fields)
    // Only once, on load.
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  if (persons.length === 0 || !slot) return null
  return createPortal(
    <div className="orc-field orc-demo-picker">
      <label htmlFor="demoPerson">
        <span className="orc-demo-tag">{t('Demo')}</span> {title ?? t('Testperson übernehmen')}
      </label>
      <select
        id="demoPerson"
        value={chosen}
        onChange={(e) => {
          setChosen(e.target.value)
          if (e.target.value !== '') apply(persons[Number(e.target.value)], fields)
        }}
      >
        <option value="">{t('— manuell eingeben —')}</option>
        {persons.map((p, i) => (
          <option key={i} value={String(i)}>
            {labelKey ? p[labelKey] : `${p.givenNames} ${p.familyName} (${p.email || p.kvnr || p.personId})`}
          </option>
        ))}
      </select>
    </div>,
    slot,
  )
}

function apply(person: Person, fields: Record<string, string>) {
  for (const [inputId, key] of Object.entries(fields)) {
    const input = document.getElementById(inputId) as HTMLInputElement | null
    if (input && person[key] !== undefined) input.value = person[key] ?? ''
  }
}
