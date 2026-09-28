import { useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'
import { t } from '../../texts'
import { StepActions } from '../../components/PhoneFrame'

interface EmailLookupFormProps {
  onSubmit: (email: string) => void
  error?: string
  /** Demo-only: every register person, offered as a picker that fills the email field; the first is prefilled. */
  demoPersons?: DemoPerson[]
}

/**
 * toolId=auth-sms-lookup / step=auth: "Login ohne DPoP". Resolves the account by email and sends
 * a TAN to its enrolled phone number.
 */
export function EmailLookupForm({ onSubmit, error, demoPersons }: EmailLookupFormProps) {
  const [email, setEmail] = useState(demoPersons?.[0]?.email ?? '')

  function selectPerson(person: DemoPerson) {
    setEmail(person.email ?? '')
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(email)
  }

  return (
    <div className="card">
      <h2>{t('Mit E-Mail-Adresse und SMS-Code anmelden')}</h2>
      <p>{t('Geben Sie die E-Mail-Adresse Ihres Kontos ein, um eine TAN an die hinterlegte Telefonnummer zu erhalten.')}</p>
      {error && <div className="hint">{error}</div>}
      <form id="sms-lookup" onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <DemoPersonPicker demoPersons={demoPersons} onSelect={selectPerson} />
        <div className="form-group">
          <label htmlFor="email">{t('E-Mail-Adresse')}</label>
          <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
        </div>
        <StepActions>
          <button type="submit" form="sms-lookup">{t('TAN anfordern')}</button>
        </StepActions>
      </form>
    </div>
  )
}
