import { useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'
import { t } from '../../texts'
import { StepActions } from '../../components/PhoneFrame'

interface EmailCodeLookupFormProps {
  onSubmit: (email: string) => void
  error?: string
  /** Demo-only: every register person, offered as a picker that fills the email field; the first is prefilled. */
  demoPersons?: DemoPerson[]
}

/**
 * toolId=auth-email-lookup / step=auth: "Login ohne DPoP". Resolves the account by email and
 * sends a confirmation code to that address.
 */
export function EmailCodeLookupForm({ onSubmit, error, demoPersons }: EmailCodeLookupFormProps) {
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
      <h2>{t('Mit E-Mail-Code anmelden')}</h2>
      <p>{t('Geben Sie die E-Mail-Adresse Ihres Kontos ein, um einen Bestätigungscode an diese Adresse zu erhalten.')}</p>
      {error && <div className="hint">{error}</div>}
      <form id="email-lookup" onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <DemoPersonPicker demoPersons={demoPersons} onSelect={selectPerson} />
        <div className="form-group">
          <label htmlFor="email">{t('E-Mail-Adresse')}</label>
          <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
        </div>
        <StepActions>
          <button type="submit" form="email-lookup">{t('Code anfordern')}</button>
        </StepActions>
      </form>
    </div>
  )
}
