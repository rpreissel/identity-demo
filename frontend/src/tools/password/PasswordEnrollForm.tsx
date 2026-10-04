import { useEffect, useState } from 'react'
import { t } from '../../texts'
import { Tx } from '../../Tx'
import { DemoNote } from '../../components/DemoArea'
import { StepActions } from '../../components/PhoneFrame'

interface PasswordEnrollFormProps {
  onSubmit: (fields: { password: string }) => void
  error?: string
  /** Demo-only: the fixed password used everywhere in this demo, prefilled for testers. */
  demoPassword?: string
  /** The account already has a password: this run changes it (stepData.replaces). */
  replaces?: boolean
}

/**
 * toolId=enroll-password / step=enroll: registers a password credential. Requires a confirmed
 * account email first (requiresConfirmedEmail). A failed attempt carries no `replaces`; the form
 * then keeps what it last heard.
 */
export function PasswordEnrollForm({ onSubmit, error, demoPassword, replaces }: PasswordEnrollFormProps) {
  const [changing, setChanging] = useState(replaces ?? false)
  useEffect(() => {
    if (replaces !== undefined) setChanging(replaces)
  }, [replaces])

  const [password, setPassword] = useState(demoPassword ?? '')
  const [passwordConfirm, setPasswordConfirm] = useState(demoPassword ?? '')
  const [validationError, setValidationError] = useState('')

  useEffect(() => {
    if (demoPassword) {
      setPassword(demoPassword)
      setPasswordConfirm(demoPassword)
    }
  }, [demoPassword])

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    if (password !== passwordConfirm) {
      setValidationError(t('Die Passwörter stimmen nicht überein.'))
      return
    }
    setValidationError('')
    onSubmit({ password })
  }

  return (
    <div className="card">
      <h2>{changing ? t('Passwort ändern') : t('Passwort einrichten')}</h2>
      <p>
        {changing
          ? t('Legen Sie ein neues Passwort fest. Es ersetzt Ihr bisheriges, sobald Sie fertig sind.')
          : t('Legen Sie ein Passwort als weiteren Faktor an. Ihre bestätigte E-Mail-Adresse dient dabei als Anmeldename.')}
      </p>
      <DemoNote>
        <Tx text="Demo-Modus: Passwort ist bereits vorbelegt: {passwort}" passwort={<code>{password}</code>} />
      </DemoNote>
      {(validationError || error) && <div className="hint">{validationError || error}</div>}
      <form id="password-enroll" onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="password">{changing ? t('Neues Passwort') : t('Passwort')}</label>
          <input
            id="password"
            type="password"
            value={password}
            onChange={(e) => setPassword(e.target.value)}
            minLength={8}
            required
            autoFocus
          />
        </div>
        <div className="form-group">
          <label htmlFor="passwordConfirm">{t('Passwort wiederholen')}</label>
          <input
            id="passwordConfirm"
            type="password"
            value={passwordConfirm}
            onChange={(e) => setPasswordConfirm(e.target.value)}
            minLength={8}
            required
          />
        </div>
        <StepActions>
          <button type="submit" form="password-enroll">{changing ? t('Ändern') : t('Einrichten')}</button>
        </StepActions>
      </form>
    </div>
  )
}
