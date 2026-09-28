import { useEffect, useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'
import { t } from '../../texts'
import { Tx } from '../../Tx'
import { DemoNote } from '../../components/DemoArea'
import { StepActions } from '../../components/PhoneFrame'

interface EmailPasswordLookupFormProps {
  onSubmit: (fields: { email: string; password: string }) => void
  error?: string
  /** Demo-only: the fixed password of every enroll-password credential, prefilled for testers. */
  demoPassword?: string
  /** Demo-only: every register person, offered as a picker that fills the email field; the first is prefilled. */
  demoPersons?: DemoPerson[]
}

/**
 * toolId=auth-password-lookup / step=auth: "Login ohne DPoP". Self-verifying: email and password
 * go together in one call.
 */
export function EmailPasswordLookupForm({ onSubmit, error, demoPassword, demoPersons }: EmailPasswordLookupFormProps) {
  const [email, setEmail] = useState(demoPersons?.[0]?.email ?? '')
  const [password, setPassword] = useState(demoPassword ?? '')

  useEffect(() => {
    if (demoPassword) setPassword(demoPassword)
  }, [demoPassword])

  function selectPerson(person: DemoPerson) {
    setEmail(person.email ?? '')
  }

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit({ email, password })
  }

  return (
    <div className="card">
      <h2>{t('Mit E-Mail-Adresse und Passwort anmelden')}</h2>
      <p>{t('Geben Sie E-Mail-Adresse und Passwort Ihres Kontos ein.')}</p>
      {demoPassword && (
        <DemoNote>
          <Tx text="Demo-Modus: Passwort ist bereits vorbelegt: {passwort}" passwort={<code>{demoPassword}</code>} />
        </DemoNote>
      )}
      {error && <div className="hint">{error}</div>}
      <form id="password-lookup" onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <DemoPersonPicker demoPersons={demoPersons} onSelect={selectPerson} />
        <div className="form-group">
          <label htmlFor="email">{t('E-Mail-Adresse')}</label>
          <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
        </div>
        <div className="form-group">
          <label htmlFor="password">{t('Passwort')}</label>
          <input id="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required />
        </div>
        <StepActions>
          <button type="submit" form="password-lookup">{t('Anmelden')}</button>
        </StepActions>
      </form>
    </div>
  )
}
