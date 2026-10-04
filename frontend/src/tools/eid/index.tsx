import type { ToolModule } from '../types'
import { submitEid } from './api'
import { IdentEidForm } from './IdentEidForm'
import { attemptError } from '../stepData'
import { stepDataOf } from '../../types'
import { t } from '../../texts'

export const identEid: ToolModule = {
  toolId: 'ident-eid',
  version: 1,
  meta: { icon: '🆔' },
  explain: () => ({
    does: t('Die Online-Ausweisfunktion weist Sie mit Ihrem Personalausweis aus: Erst wird der Ausweis ausgelesen, dann geben Sie ihn mit der PIN frei.'),
    actor: t('Sie, mit dem simulierten eID-Dienst anstelle der AusweisApp.'),
  }),
  render(ctx) {
    if (ctx.step === 'input') {
      return (
        <IdentEidForm
          onSubmit={(fields) => submitEid(ctx, fields)}
          missingFields={stepDataOf(ctx.stepData, 'missing-fields')?.missingFields}
          error={attemptError(ctx)}
          demoPersons={ctx.demo?.persons}
        />
      )
    }
    return null
  },
}

const eidModules: ToolModule[] = [identEid]
export default eidModules
