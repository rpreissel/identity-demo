import { useState } from 'react'
import type { PageContext } from '../KcContext'
import { DemoPersonPicker } from './DemoPersonPicker'
import { Field } from './Field'
import { ToolForm } from './ToolForm'
import { t } from '../../texts'

/**
 * `tool-email-lookup.ftl`: first the e-mail address, then (step codeInput) the code sent to it.
 * With `addressAgain`, "Zurück" on the code shows the address again - a pure screen change.
 */
export function EmailThenCode({ kcContext }: { kcContext: PageContext<'tool-email-lookup.ftl'> }) {
  const { pageTitle: title, hint, step, demoTan, demoPersonsJson, addressAgain } = kcContext
  const [editingAddress, setEditingAddress] = useState(false)
  const codeInput = step === 'codeInput' && !editingAddress
  const onBack = step === 'codeInput' && addressAgain ? () => setEditingAddress(!editingAddress) : undefined
  return (
    <ToolForm key={codeInput ? 'code' : 'address'} kcContext={kcContext} title={title} hint={hint} onBack={onBack}>
      {codeInput ? (
        <Field id="code" label={t('Bestätigungscode')} hint={demoTan && t('Demo-Code: {wert}', { wert: demoTan })} />
      ) : (
        <>
          <DemoPersonPicker personsJson={demoPersonsJson} fields={{ email: 'email' }} />
          <Field id="email" type="email" label={t('E-Mail-Adresse')} />
        </>
      )}
    </ToolForm>
  )
}
