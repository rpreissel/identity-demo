import { t } from '../texts'
import { Tx } from '../Tx'
import type { DemoPerson } from '../types'
import { Demo } from './DemoArea'

interface DemoPersonPickerProps {
  /** Demo-only: every register person. Renders nothing when fewer than two are offered. */
  demoPersons?: DemoPerson[]
  /**
   * The Partnernummer of the person the form currently holds, '' when its fields match nobody.
   * The picker follows it instead of starting at the first person on each mount; otherwise a
   * remounted picker (going back a page) would show one person above another person's fields.
   */
  selectedPersonId?: string
  onSelect: (person: DemoPerson) => void
}

/** Shared by ident forms (ident_fsc/ident_eid) that prefill several fields at once from one persona. */
export function DemoPersonPicker({ demoPersons, selectedPersonId, onSelect }: DemoPersonPickerProps) {
  if (!demoPersons || demoPersons.length < 2) return null
  const matched = selectedPersonId === undefined || demoPersons.some((p) => p.personId === selectedPersonId)
  const selection =
    selectedPersonId === undefined ? { defaultValue: demoPersons[0].personId } : { value: matched ? selectedPersonId : '' }
  // In the App channel this renders in the demo column next to the phone (DemoArea) - still part of
  // its form's tree, so picking a person fills the fields in the phone.
  return (
    <Demo>
      <div className="form-group demo-picker">
        <label htmlFor="demoPerson">
          <Tx text="{demo} Testperson übernehmen" demo={<span className="demo-picker__tag">{t('Demo')}</span>} />
        </label>
        <select
          id="demoPerson"
          {...selection}
          onChange={(e) => {
            const person = demoPersons.find((p) => p.personId === e.target.value)
            if (person) onSelect(person)
          }}
        >
          {!matched && (
            <option value="" disabled>
              — {t('eigene Eingabe')} —
            </option>
          )}
          {demoPersons.map((person) => (
            <option key={person.personId} value={person.personId}>
              {person.givenNames} {person.familyName} ({person.kvnr ?? person.personId})
            </option>
          ))}
        </select>
      </div>
    </Demo>
  )
}
