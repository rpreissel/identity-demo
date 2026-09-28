import { useEffect, useState } from 'react'
import { t } from '../../texts'
import { Tx } from '../../Tx'
import { DemoNote } from '../../components/DemoArea'
import { StepActions } from '../../components/PhoneFrame'

interface PasswordLoginFormProps {
  onSubmit: (fields: { password: string }) => void
  error?: string
  /** Demo-only: the fixed password of every enroll-password credential, prefilled for testers. */
  demoPassword?: string
}

/**
 * toolId=auth-password / step=auth: checks the password against the account's credential. The
 * device is already recognized, so no identifier is needed.
 */
export function PasswordLoginForm({ onSubmit, error, demoPassword }: PasswordLoginFormProps) {
  const [password, setPassword] = useState(demoPassword ?? '')

  useEffect(() => {
    if (demoPassword) setPassword(demoPassword)
  }, [demoPassword])

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit({ password })
  }

  return (
    <div className="card">
      <h2>{t('Mit Passwort anmelden')}</h2>
      <p>{t('Geben Sie Ihr Passwort ein.')}</p>
      {demoPassword && (
        <DemoNote>
          <Tx text="Demo-Modus: Passwort ist bereits vorbelegt: {passwort}" passwort={<code>{demoPassword}</code>} />
        </DemoNote>
      )}
      {error && <div className="hint">{error}</div>}
      <form id="password-login" onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="password">{t('Passwort')}</label>
          <input id="password" type="password" value={password} onChange={(e) => setPassword(e.target.value)} required autoFocus />
        </div>
        <StepActions>
          <button type="submit" form="password-login">{t('Anmelden')}</button>
        </StepActions>
      </form>
    </div>
  )
}
