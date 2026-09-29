import type { PageContext } from '../KcContext'
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
  const { pageTitle: title } = kcContext
  return (
    <ToolForm
      kcContext={kcContext}
      title={title}
      hint={t('Geben Sie Ihre Versichertennummer und das Einmalkennwort aus unserem Brief ein.')}
      submitLabel={t('Anmelden')}
    >
      <Field id="kvnr" label={t('Versichertennummer')} />
      <PartnerNumber />
      <Field id="code" label={t('Einmalkennwort')} autoComplete="one-time-code" placeholder="XXXX-XXXX-XXXX" required />
    </ToolForm>
  )
}
