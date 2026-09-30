import { useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'
import { t } from '../../texts'
import { StepActions } from '../../components/PhoneFrame'

interface IdentKvnrFormProps {
  /** Either the KVNR or - only without one, for a Partner (ADR-34) - the Partnernummer. */
  onSubmit: (identifier: { kvnr: string } | { partnerNumber: string }) => void
  /** Skips the step (ToolRenderContext.onSkip), next to the submit button, where the decision is made. */
  onSkip?: () => void
  skipLabel: string
  error?: string
  /** Demo-only: all register persons, so a mismatching number is easy to try out too; the first is prefilled. */
  demoPersons?: DemoPerson[]
}

/**
 * toolId=ident-kvnr / step=input: the number an attestation cannot carry. Runs only after an
 * identity was attested (docs/12-entscheidungen.md ADR-18) and assigns the account to that
 * person's register record - it proves nothing on its own.
 */
export function IdentKvnrForm({ onSubmit, onSkip, skipLabel, error, demoPersons }: IdentKvnrFormProps) {
  const first = demoPersons?.[0]
  const [kvnr, setKvnr] = useState(first?.kvnr ?? '')
  const [partnerNumber, setPartnerNumber] = useState(first?.kvnr ? '' : (first?.personId ?? ''))
  // The KVNR is asked for first; the Partnernummer only when there is none (ADR-34).
  const [withoutKvnr, setWithoutKvnr] = useState(first != null && !first.kvnr)

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(withoutKvnr ? { partnerNumber } : { kvnr })
  }

  function selectPerson(person: DemoPerson) {
    setWithoutKvnr(!person.kvnr)
    if (person.kvnr) setKvnr(person.kvnr)
    else setPartnerNumber(person.personId)
  }

  return (
    <div className="card">
      <h2>{t('Konto zuordnen')}</h2>
      <p>
        {t(
          'Ihre Identität ist bereits nachgewiesen. Mit Ihrer Versichertennummer - oder ohne sie mit Ihrer Partnernummer - ' +
            'verbinden wir Ihr Konto mit Ihrem Eintrag im Personenverzeichnis. Die Nummer muss zu der nachgewiesenen Person gehören.',
        )}
      </p>
      <p className="note">
        {t(
          'Der Schritt ist freiwillig: Mit „{skipLabel}" geht die Registrierung ohne diese Zuordnung ' +
            'weiter, das Konto bleibt nutzbar.',
          { skipLabel },
        )}
      </p>
      {error && <div className="hint">{error}</div>}
      <form id="kvnr" onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <DemoPersonPicker demoPersons={demoPersons} onSelect={selectPerson} />
        {withoutKvnr ? (
          <div className="form-group">
            <label htmlFor="partnerNumber-input">{t('Partnernummer')}</label>
            <input id="partnerNumber-input" value={partnerNumber} placeholder="P000000000" onChange={(e) => setPartnerNumber(e.target.value)} required />
            <button type="button" className="text-button" onClick={() => setWithoutKvnr(false)}>
              {t('Ich habe doch eine Versichertennummer')}
            </button>
          </div>
        ) : (
          <div className="form-group">
            <label htmlFor="kvnr-input">{t('Versichertennummer')}</label>
            <input id="kvnr-input" value={kvnr} onChange={(e) => setKvnr(e.target.value)} required />
            <button type="button" className="text-button" onClick={() => setWithoutKvnr(true)}>
              {t('Ich habe keine Versichertennummer')}
            </button>
          </div>
        )}
        <StepActions>
          <button type="submit" form="kvnr">{t('Zuordnen')}</button>
          {onSkip && (
            <button type="button" className="secondary" onClick={onSkip}>
              {skipLabel}
            </button>
          )}
        </StepActions>
      </form>
    </div>
  )
}
