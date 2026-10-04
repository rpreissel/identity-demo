import { useState } from 'react'
import type { PageContext } from '../KcContext'
import { DemoPersonPicker } from '../components/DemoPersonPicker'
import { Field } from '../components/Field'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/**
 * `tool-sms-enroll.ftl`: the phone number, then (step tanInput) the code sent to it. "Zurück" on the
 * code shows the number again - a pure screen change.
 */
export function ToolSmsEnroll({ kcContext }: { kcContext: PageContext<'tool-sms-enroll.ftl'> }) {
  const { pageTitle: title, hint, step, demoTan, demoPersonsJson, replaces } = kcContext
  const [editingNumber, setEditingNumber] = useState(false)
  const tanInput = step === 'tanInput' && !editingNumber
  const onBack = step === 'tanInput' ? () => setEditingNumber(!editingNumber) : undefined
  return (
    <ToolForm key={tanInput ? 'tan' : 'number'} kcContext={kcContext} title={title} hint={hint} onBack={onBack}>
      {replaces && <p className="orc-hint">{t('Die neue Telefonnummer ersetzt Ihre bisherige, sobald Sie den Code bestätigt haben.')}</p>}
      {tanInput ? (
        <Field id="tan" label={t('SMS-Code')} autoComplete="one-time-code" hint={demoTan && t('Demo-Code: {wert}', { wert: demoTan })} />
      ) : (
        <>
          <DemoPersonPicker personsJson={demoPersonsJson} fields={{ phoneNumber: 'phoneNumber' }} />
          <Field id="phoneNumber" type="tel" label={t('Telefonnummer')} autoComplete="tel" />
        </>
      )}
    </ToolForm>
  )
}
