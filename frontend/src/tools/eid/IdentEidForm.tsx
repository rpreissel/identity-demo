import { useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'
import type { EidCard, EidFields } from './api'
import { t } from '../../texts'
import { DemoNote } from '../../components/DemoArea'
import { StepActions } from '../../components/PhoneFrame'

/** What the first page collects - everything the backend stages before it asks for `pin`. */
const CARD_FIELDS: (keyof EidCard)[] = ['familyName', 'givenNames', 'birthDate', 'streetAddress', 'postalCode', 'locality', 'restrictedId']

interface IdentEidFormProps {
  onSubmit: (fields: Partial<EidFields>) => void
  /** stepData.missingFields, when the step carries it - undefined after a failed attempt. */
  missingFields?: string[]
  error?: string
  /** Demo-only: every register person, offered as a picker that fills the whole card at once. */
  demoPersons?: DemoPerson[]
}

type Page = 'card' | 'pin'

function cardOf(person?: DemoPerson): EidCard {
  return {
    familyName: person?.familyName ?? '',
    givenNames: person?.givenNames ?? '',
    birthDate: person?.birthDate ?? '',
    streetAddress: person?.streetAddress ?? '',
    postalCode: person?.postalCode ?? '',
    locality: person?.locality ?? '',
    restrictedId: person?.restrictedId ?? '',
  }
}

/**
 * toolId=ident-eid / step=input (docs/06-ablaeufe.md #6). The backend keeps one step; the two pages
 * here (the card read, then the PIN) are this form's choice (docs/10-frontend.md). The backend
 * checks the card data the moment it is complete and only then asks for `pin`, so `missingFields`
 * says which page is due. A failed attempt carries no `missingFields`; the form then stays on the
 * page it was submitted from.
 */
export function IdentEidForm({ onSubmit, missingFields, error, demoPersons }: IdentEidFormProps) {
  // Prefilled from the first register persona - no hard-coded test person of our own.
  const [card, setCard] = useState<EidCard>(() => cardOf(demoPersons?.[0]))
  const [pin, setPin] = useState('123456')
  const [submittedFrom, setSubmittedFrom] = useState<Page>('card')
  const [editing, setEditing] = useState(false)

  const dueByBackend: Page | undefined = missingFields
    ? missingFields.some((field) => (CARD_FIELDS as string[]).includes(field)) ? 'card' : 'pin'
    : undefined
  const page: Page = editing ? 'card' : (dueByBackend ?? submittedFrom)

  // The card's pseudonym names the persona; a changed one simulates another card (ADR-19).
  const selectedPersonId = demoPersons?.find((p) => p.restrictedId === card.restrictedId)?.personId ?? ''

  function update(field: keyof EidCard) {
    return (event: React.ChangeEvent<HTMLInputElement>) => setCard({ ...card, [field]: event.target.value })
  }

  function submitCard(event: React.FormEvent) {
    event.preventDefault()
    setSubmittedFrom('card')
    setEditing(false)
    onSubmit(card)
  }

  function submitPin(event: React.FormEvent) {
    event.preventDefault()
    setSubmittedFrom('pin')
    onSubmit({ pin })
  }

  if (page === 'card') {
    return (
      <div className="card">
        <h2>{t('eID-Karte auflegen')}</h2>
        <p>
          {t(
            'Halten Sie Ihren Personalausweis an das Lesegerät. Die Karte bezeugt, wer Sie sind - eine ' +
              'Zuordnung per Versichertennummer oder Partnernummer ist ein eigener Schritt danach.',
          )}
        </p>
        <DemoNote>{t('Demo-Modus: Das Auslesen der Karte wird simuliert.')}</DemoNote>
        <form id="eid-card" onSubmit={submitCard} className="form-grid" style={{ marginTop: '1rem' }}>
          <DemoPersonPicker demoPersons={demoPersons} selectedPersonId={selectedPersonId} onSelect={(person) => setCard(cardOf(person))} />
          <div className="form-group">
            <label htmlFor="eid-familyName">{t('Nachname')}</label>
            <input id="eid-familyName" value={card.familyName} onChange={update('familyName')} required />
          </div>
          <div className="form-group">
            <label htmlFor="eid-givenNames">{t('Vorname')}</label>
            <input id="eid-givenNames" value={card.givenNames} onChange={update('givenNames')} required />
          </div>
          <div className="form-group">
            <label htmlFor="eid-birthDate">{t('Geburtsdatum')}</label>
            <input id="eid-birthDate" type="date" value={card.birthDate} onChange={update('birthDate')} required />
          </div>
          <div className="form-group">
            <label htmlFor="eid-streetAddress">{t('Straße und Hausnummer')}</label>
            <input id="eid-streetAddress" value={card.streetAddress} onChange={update('streetAddress')} required />
          </div>
          <div className="form-group">
            <label htmlFor="eid-postalCode">{t('PLZ')}</label>
            <input id="eid-postalCode" value={card.postalCode} onChange={update('postalCode')} required />
          </div>
          <div className="form-group">
            <label htmlFor="eid-locality">{t('Ort')}</label>
            <input id="eid-locality" value={card.locality} onChange={update('locality')} required />
          </div>
          {/* Editierbar, obwohl eine echte Karte den Wert fest mitbringt: In der Demo ist das Feld der
              einzige Weg, eine andere Karte derselben Person zu simulieren (ADR-19: neuer Wert,
              gleiches Konto) oder dieselbe Karte ein zweites Mal aufzulegen (Wiedererkennung). */}
          <div className="form-group">
            <label htmlFor="eid-restricted-id">{t('Restricted-ID (kartengebunden)')}</label>
            <input id="eid-restricted-id" value={card.restrictedId} onChange={update('restrictedId')} required />
            <span className="hint">
              {t(
                'Das kartengebundene Pseudonym. Eine neue Karte derselben Person bringt einen neuen Wert ' +
                  'mit - zum Ausprobieren hier änderbar.',
              )}
            </span>
          </div>
          {error && <div className="hint">{error}</div>}
          <StepActions>
            <button type="submit" form="eid-card">{t('Karte auflegen (simuliert)')}</button>
          </StepActions>
        </form>
      </div>
    )
  }

  return (
    <div className="card">
      <h2>{t('eID-PIN eingeben')}</h2>
      <p>
        {t('Geben Sie Ihre sechsstellige eID-PIN ein.')}{' '}
        {/* Going back to the card belongs to what this screen shows, not to the ways on. */}
        <button type="button" className="text-button" onClick={() => setEditing(true)}>
          {t('Angaben ändern')}
        </button>
      </p>
      <form id="eid-pin-form" onSubmit={submitPin} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="eid-pin">{t('PIN')}</label>
          <input id="eid-pin" value={pin} onChange={(e) => setPin(e.target.value)} autoComplete="off" required />
        </div>
        {error && <div className="hint">{error}</div>}
        <StepActions>
          <button type="submit" form="eid-pin-form">{t('Identifizieren')}</button>
        </StepActions>
      </form>
    </div>
  )
}
