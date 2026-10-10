import { useEffect, useState, type ReactNode } from 'react'
import { DemoNote } from '../../components/DemoArea'
import { StepActions } from '../../components/PhoneFrame'

/** What a tool says on its code page; the texts stay at the caller as t('…') literals. */
export interface OneTimeCodeCopy {
  formId: string
  fieldId: string
  title: string
  intro: string
  label: string
  placeholder: string
  submit: string
  /** The demo note: with the issued code it says it is prefilled, without it where to find it. */
  demoNote: (demoCode?: string) => ReactNode
}

interface OneTimeCodeFormProps {
  onSubmit: (code: string) => void
  error?: string
  /** Demo-only: the just-issued code, pre-filled here so testers don't need server-log access. */
  demoTan?: string
  copy: OneTimeCodeCopy
}

/** The page that asks for a six-digit code sent by SMS or email. */
export function OneTimeCodeForm({ onSubmit, error, demoTan, copy }: OneTimeCodeFormProps) {
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
      <h2>{copy.title}</h2>
      <p>{copy.intro}</p>
      <DemoNote>{copy.demoNote(demoTan)}</DemoNote>
      {error && (
        <div className="hint" style={{ marginTop: '0.75rem' }}>
          {error}
        </div>
      )}
      <form id={copy.formId} onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor={copy.fieldId}>{copy.label}</label>
          <input
            id={copy.fieldId}
            value={code}
            onChange={(e) => setCode(e.target.value)}
            placeholder={copy.placeholder}
            maxLength={6}
            required
            autoFocus
          />
        </div>
        <StepActions>
          <button type="submit" form={copy.formId}>{copy.submit}</button>
        </StepActions>
      </form>
    </div>
  )
}
