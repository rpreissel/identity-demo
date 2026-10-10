import { t } from '../../texts'
import { Tx } from '../../Tx'
import { OneTimeCodeForm } from '../shared/OneTimeCodeForm'

interface EmailCodeInputFormProps {
  onSubmit: (code: string) => void
  error?: string
  /** Demo-only: the just-issued code, pre-filled here so testers don't need server-log access. */
  demoTan?: string
}

/** Shared by confirm-email/codeInput and auth-email/auth. */
export function EmailCodeInputForm(props: EmailCodeInputFormProps) {
  return (
    <OneTimeCodeForm
      {...props}
      copy={{
        formId: 'email-code',
        fieldId: 'code',
        title: t('Bestätigungscode eingeben'),
        intro: t('Wir haben Ihnen soeben einen Bestätigungscode per E-Mail geschickt. Geben Sie ihn hier ein.'),
        label: t('Code'),
        placeholder: t('6-stelliger Code'),
        submit: t('Code bestätigen'),
        demoNote: (demoTan) => demoTan
          ? <Tx text="{modus} Der Code ist bereits vorbelegt: {code}" modus={<strong>{t('Demo-Modus:')}</strong>} code={<code>{demoTan}</code>} />
          : <Tx text="{modus} Der Code wird nur ins Server-Log geschrieben ({log})." modus={<strong>{t('Demo-Modus:')}</strong>} log={<code>[MOCK EMAIL] ...</code>} />,
      }}
    />
  )
}
