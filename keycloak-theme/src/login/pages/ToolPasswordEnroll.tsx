import type { PageContext } from '../KcContext'
import { Field } from '../components/Field'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/** `tool-password-enroll.ftl`: choosing a new password. */
export function ToolPasswordEnroll({ kcContext }: { kcContext: PageContext<'tool-password-enroll.ftl'> }) {
  const { pageTitle: title, hint, demoPassword, replaces } = kcContext
  return (
    <ToolForm kcContext={kcContext} title={title} hint={hint}>
      {replaces && <p className="orc-hint">{t('Das neue Passwort ersetzt Ihr bisheriges, sobald Sie fertig sind.')}</p>}
      <Field id="password" type="password" label={t('Neues Passwort')} autoComplete="new-password" hint={demoPassword && t('Demo-Passwort: {wert}', { wert: demoPassword })} />
    </ToolForm>
  )
}
