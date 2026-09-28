import { t } from '../texts'
import { Demo } from './DemoArea'
import { DiagramTrigger } from './DiagramHint'
import { JOURNEY_DIAGRAMS } from '../journeyDiagrams'

/**
 * The journeys behind a screen's buttons, as diagrams in the demo column - the phone or website
 * shows the buttons a real client has, the demo column what each one sets going.
 */
export function ButtonDiagrams({ entries }: { entries: Array<{ label: string; diagram: keyof typeof JOURNEY_DIAGRAMS }> }) {
  return (
    <Demo background>
      {entries.map((entry) => (
        <p className="demo-diagram" key={entry.diagram}>
          {entry.label}
          <DiagramTrigger spec={JOURNEY_DIAGRAMS[entry.diagram]} label={t('Ablauf "{abschnitt}" als Diagramm anzeigen', { abschnitt: entry.label })} />
        </p>
      ))}
    </Demo>
  )
}
