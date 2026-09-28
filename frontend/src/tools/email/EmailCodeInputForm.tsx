import { useEffect, useState } from 'react'
import { t } from '../../texts'
import { Tx } from '../../Tx'
import { DemoNote } from '../../components/DemoArea'
import { StepActions } from '../../components/PhoneFrame'

interface EmailCodeInputFormProps {
  onSubmit: (code: string) => void
  error?: string
  /** Demo-only: the just-issued code, pre-filled here so testers don't need server-log access. */
  demoTan?: string
}

/** Shared by confirm-email/codeInput and auth-email/auth. */
export function EmailCodeInputForm({ onSubmit, error, demoTan }: EmailCodeInputFormProps) {
  const [code, setCode] = useState(demoTan ?? '')

  // A fresh code was issued (new tool session, or a resend) - replace whatever was typed before.
  useEffect(() => {
    if (demoTan) setCode(demoTan)
  }, [demoTan])

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    onSubmit(code)
  }

  return (
    <div className="card">
      <h2>{t('Bestätigungscode eingeben')}</h2>
      <p>{t('Wir haben Ihnen soeben einen Bestätigungscode per E-Mail geschickt. Geben Sie ihn hier ein.')}</p>
      <DemoNote>
        {demoTan ? (
          <Tx text="{modus} Der Code ist bereits vorbelegt: {code}" modus={<strong>{t('Demo-Modus:')}</strong>} code={<code>{demoTan}</code>} />
        ) : (
          <Tx text="{modus} Der Code wird nur ins Server-Log geschrieben ({log})." modus={<strong>{t('Demo-Modus:')}</strong>} log={<code>[MOCK EMAIL] ...</code>} />
        )}
      </DemoNote>
      {error && (
        <div className="hint" style={{ marginTop: '0.75rem' }}>
          {error}
        </div>
      )}
      <form id="email-code" onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="code">{t('Code')}</label>
          <input
            id="code"
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder={t('6-stelliger Code')}
            maxLength={6}
            required
            autoFocus
          />
        </div>
        <StepActions>
          <button type="submit" form="email-code">{t('Code bestätigen')}</button>
        </StepActions>
      </form>
    </div>
  )
}
