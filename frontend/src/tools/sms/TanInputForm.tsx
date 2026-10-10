import { t } from '../../texts'
import { Tx } from '../../Tx'
import { OneTimeCodeForm } from '../shared/OneTimeCodeForm'

interface TanInputFormProps {
  onSubmit: (tan: string) => void
  error?: string
  /** Demo-only: the just-issued TAN, pre-filled here so testers don't need server-log access. */
  demoTan?: string
}

/** Shared by enroll-sms/tanInput and auth-sms/auth. */
export function TanInputForm(props: TanInputFormProps) {
  return (
    <OneTimeCodeForm
      {...props}
      copy={{
        formId: 'tan-input',
        fieldId: 'tan',
        title: t('TAN eingeben'),
        intro: t('Wir haben Ihnen soeben eine TAN per SMS geschickt. Geben Sie sie hier ein.'),
        label: t('TAN'),
        placeholder: t('6-stellige TAN'),
        submit: t('TAN bestätigen'),
        demoNote: (demoTan) => demoTan
          ? <Tx text="{modus} Die TAN ist bereits vorbelegt: {tan}" modus={<strong>{t('Demo-Modus:')}</strong>} tan={<code>{demoTan}</code>} />
          : <Tx text="{modus} Die TAN wird nur ins Server-Log geschrieben ({log})." modus={<strong>{t('Demo-Modus:')}</strong>} log={<code>[MOCK SMS] ...</code>} />,
      }}
    />
  )
}
