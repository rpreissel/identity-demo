import { useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'
import { t } from '../../texts'
import { StepActions } from '../../components/PhoneFrame'

interface EmailEnrollFormProps {
  onSubmit: (email: string) => void
  error?: string
  /** Demo-only: every register person, offered as a picker that fills the email field; the first is prefilled. */
  demoPersons?: DemoPerson[]
}

/**
 * toolId=confirm-email / step=input: confirms control over an email address. The account keeps
 * it; no method is created.
 */
export function EmailEnrollForm({ onSubmit, error, demoPersons }: EmailEnrollFormProps) {
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
      <h2>{t('E-Mail-Adresse einrichten')}</h2>
      <p>{t('Geben Sie Ihre E-Mail-Adresse ein, um einen Bestätigungscode zu erhalten.')}</p>
      {error && <div className="hint">{error}</div>}
      <form id="email-enroll" onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <DemoPersonPicker demoPersons={demoPersons} onSelect={selectPerson} />
        <div className="form-group">
          <label htmlFor="email">{t('E-Mail-Adresse')}</label>
          <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
        </div>
        <StepActions>
          <button type="submit" form="email-enroll">{t('Code senden')}</button>
        </StepActions>
      </form>
    </div>
  )
}
