import { useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'
import { t } from '../../texts'
import { StepActions } from '../../components/PhoneFrame'

interface SmsEnrollFormProps {
  onSubmit: (phoneNumber: string) => void
  error?: string
  /** Demo-only: every register person, offered as a picker that fills the number; the first is prefilled. */
  demoPersons?: DemoPerson[]
}

// FE-9: client-side pre-validation; the backend still rejects malformed numbers with 400.
const PHONE_PATTERN = /^\+?[0-9]{6,20}$/

function isValidPhoneNumber(value: string): boolean {
  return PHONE_PATTERN.test(value.replace(/\s+/g, ''))
}

/** toolId=enroll-sms / step=enroll (docs/06-ablaeufe.md #4): registers a new phone number. */
export function SmsEnrollForm({ onSubmit, error, demoPersons }: SmsEnrollFormProps) {
  const [phoneNumber, setPhoneNumber] = useState(demoPersons?.[0]?.phoneNumber ?? '')
  const [validationError, setValidationError] = useState('')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (!isValidPhoneNumber(phoneNumber)) {
      setValidationError(t('Bitte eine gültige Telefonnummer eingeben (z. B. {beispiel}).', { beispiel: '+49 170 1234567' }))
      return
    }
    setValidationError('')
    onSubmit(phoneNumber)
  }

  return (
    <div className="card">
      <h2>{t('SMS als zweiten Faktor einrichten')}</h2>
      <p>{t('Geben Sie Ihre Telefonnummer ein, um einen Verifizierungscode zu erhalten.')}</p>
      <form id="sms-enroll" onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <DemoPersonPicker demoPersons={demoPersons} onSelect={(person) => setPhoneNumber(person.phoneNumber ?? '')} />
        <div className="form-group">
          <label htmlFor="phoneNumber">{t('Telefonnummer')}</label>
          <input
            id="phoneNumber"
            type="tel"
            value={phoneNumber}
            onChange={(e) => setPhoneNumber(e.target.value)}
            placeholder="+49 170 xxxxxxxx"
            required
          />
        </div>
        {(validationError || error) && <div className="hint">{validationError || error}</div>}
        <StepActions>
          <button type="submit" form="sms-enroll">{t('Code senden')}</button>
        </StepActions>
      </form>
    </div>
  )
}
