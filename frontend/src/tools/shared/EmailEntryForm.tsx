import { useState } from 'react'
import { DemoPersonPicker } from '../../components/DemoPersonPicker'
import type { DemoPerson } from '../../types'
import { t } from '../../texts'
import { StepActions } from '../../components/PhoneFrame'

/** What a lookup tool says on its address page; the texts stay at the caller as t('…') literals. */
export interface EmailEntryCopy {
  formId: string
  title: string
  intro: string
  submit: string
}

interface EmailEntryFormProps {
  onSubmit: (email: string) => void
  error?: string
  /** Demo-only: every register person, offered as a picker that fills the email field; the first is prefilled. */
  demoPersons?: DemoPerson[]
  copy: EmailEntryCopy
}

/** The first page of a lookup login: the account's email address, to which a code is then sent. */
export function EmailEntryForm({ onSubmit, error, demoPersons, copy }: EmailEntryFormProps) {
  const [email, setEmail] = useState(demoPersons?.[0]?.email ?? '')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(email)
  }

  return (
    <div className="card">
      <h2>{copy.title}</h2>
      <p>{copy.intro}</p>
      {error && <div className="hint">{error}</div>}
      <form id={copy.formId} onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <DemoPersonPicker demoPersons={demoPersons} onSelect={(person) => setEmail(person.email ?? '')} />
        <div className="form-group">
          <label htmlFor="email">{t('E-Mail-Adresse')}</label>
          <input id="email" type="email" value={email} onChange={(e) => setEmail(e.target.value)} required autoFocus />
        </div>
        <StepActions>
          <button type="submit" form={copy.formId}>{copy.submit}</button>
        </StepActions>
      </form>
    </div>
  )
}
