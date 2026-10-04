import { submitViaPatch } from '../shared/defaultApi'
import type { ToolModule } from '../types'
import { IdentKvnrForm } from './IdentKvnrForm'
import { attemptError } from '../stepData'
import { t } from '../../texts'

/** Kein Verfahrenswechsel, sondern ein freiwilliger Schritt: Beschriftung des Auswegs, auch im Rahmen. */
const SKIP_LABEL = t('Jetzt nicht')

export const identKvnr: ToolModule = {
  toolId: 'ident-kvnr',
  version: 1,
  meta: {
    icon: '🗂️',
    // Wer abbricht, registriert weiter - das Konto bleibt Interessent (ADR-10/ADR-18). Gesetzt
    // heisst zugleich: Dieses Tool zeichnet den Ausweg selbst, die Rahmen-UI laesst ihn weg.
    skipLabel: SKIP_LABEL,
  },
  explain: () => ({
    does: t('Mit der Versichertennummer wird Ihr Konto einer versicherten Person im Personenverzeichnis zugeordnet. Der Schritt ist freiwillig.'),
    actor: t('Sie in der App, danach das Tool ident-kvnr mit einer Anfrage an das Personenverzeichnis (simuliert).'),
  }),
  render(ctx) {
    if (ctx.step === 'input') {
      return (
        <IdentKvnrForm
          onSubmit={(identifier) => submitViaPatch(ctx, identifier)}
          onSkip={ctx.onSkip}
          skipLabel={SKIP_LABEL}
          error={attemptError(ctx)}
          demoPersons={ctx.demo?.persons}
        />
      )
    }
    return null
  },
}

const kvnrModules: ToolModule[] = [identKvnr]
export default kvnrModules
