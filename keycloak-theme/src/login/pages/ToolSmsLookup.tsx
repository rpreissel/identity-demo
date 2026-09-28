import type { PageContext } from '../KcContext'
import { DemoPersonPicker } from '../components/DemoPersonPicker'
import { Field } from '../components/Field'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/** `tool-sms-lookup.ftl`: the e-mail address finds the account, then (step tanInput) the SMS code. */
export function ToolSmsLookup({ kcContext }: { kcContext: PageContext<'tool-sms-lookup.ftl'> }) {
  const { pageTitle: title, hint, step, demoTan, demoPersonsJson } = kcContext
  return (
    <ToolForm kcContext={kcContext} title={title} hint={hint}>
      {step === 'tanInput' ? (
        <Field id="tan" label={t('SMS-Code')} autoComplete="one-time-code" hint={demoTan && t('Demo-Code: {wert}', { wert: demoTan })} />
      ) : (
        <>
          <DemoPersonPicker personsJson={demoPersonsJson} fields={{ email: 'email' }} />
          <Field id="email" type="email" label={t('E-Mail-Adresse')} autoComplete="email" />
        </>
      )}
    </ToolForm>
  )
}
