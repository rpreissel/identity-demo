import type { PageContext } from '../KcContext'
import { DemoPersonPicker } from '../components/DemoPersonPicker'
import { Field } from '../components/Field'
import { PartnerNumber } from '../components/PartnerNumber'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'

/**
 * `tool-auth-invite.ftl`: the number and the one-time password from the letter, in one step
 * (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). The KVNR comes first; the Partnernummer only
 * counts without one (ADR-34).
 */
export function ToolAuthInvite({ kcContext }: { kcContext: PageContext<'tool-auth-invite.ftl'> }) {
  const { pageTitle: title, demoInvitationsJson } = kcContext
  return (
    <ToolForm
      kcContext={kcContext}
      title={title}
      hint={t('Geben Sie Ihre Versichertennummer und das Einmalkennwort aus unserem Brief ein.')}
      submitLabel={t('Anmelden')}
    >
      <DemoPersonPicker
        personsJson={demoInvitationsJson}
        labelKey="label"
        title={t('Einladung übernehmen')}
        fields={{ kvnr: 'kvnr', partnerNumber: 'partnerNumber', code: 'code' }}
      />
      <Field id="kvnr" label={t('Versichertennummer')} />
      <PartnerNumber />
      <Field id="code" label={t('Einmalkennwort')} autoComplete="one-time-code" placeholder="XXXX-XXXX-XXXX" required />
    </ToolForm>
  )
}
