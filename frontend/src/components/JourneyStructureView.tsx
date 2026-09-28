import { resolveText, t } from '../texts'
import type { ReactNode } from 'react'
import type { JourneyDebugStep, Next } from '../types'
import { shorten } from '../format'
import { Disclosure } from './Disclosure'
import { DiagramTrigger } from './DiagramHint'
import { CURRENT_STEP_BY_STATE_TYPE, diagramKeyForState, JOURNEY_DIAGRAMS } from '../journeyDiagrams'

interface JourneyStructureViewProps {
  channelSessionId?: string
  channelState?: string
  /**
   * The running journey chain, outermost first (docs/tool_api/Envelope.kt, JourneyDebugStep).
   * Only present once demo.journeys was returned.
   */
  journeys?: JourneyDebugStep[]
  /**
   * Where the channel is headed: a ToolSession (`type: 'tool'`) or an orchestrator screen
   * (`type: 'orchestrator'`, e.g. a selection), both shown as the innermost box.
   */
  next?: Next
}

interface Level {
  label: string
  detail: string
  /** Only the Journey level carries this: the diagram shows that journey's shape. */
  hint?: ReactNode
  /**
   * Only the innermost level carries this: demo-only, why it is up now (DemoStepReason). Never the
   * screen's own title, which the screen already shows.
   */
  note?: string
}

/**
 * Nests levels with the containment visual of the Willkommen explanation (App.css .nesting-box),
 * starting at `baseDepth`. The Channel box, rendered separately, is depth 1.
 */
function nest(levels: Level[], baseDepth: number): ReactNode {
  return levels.reduceRight<ReactNode>((inner, level, index) => {
    const depth = Math.min(baseDepth + index, 3)
    return (
      <div className={`nesting-box nesting-box--${depth}`} key={index}>
        <span className="nesting-label">
          {level.label} <em>{level.detail}</em> {level.hint}
        </span>
        {level.note && <p className="nesting-note">{level.note}</p>}
        {inner}
      </div>
    )
  }, null)
}

/**
 * Session identity, live status and current step, nested as Channel ⊃ Journey ⊃ SubJourney ⊃
 * Tool - including any SUSPENDED parent journey (e.g. a step-up gate parked mid-way while its
 * sub-journey runs) that would otherwise be invisible from the outside. Collapsed: structure, not
 * the journey itself.
 */
export function JourneyStructureView({ channelSessionId, channelState, journeys, next }: JourneyStructureViewProps) {
  if (!channelSessionId) return null

  const levels: Level[] = []

  journeys?.forEach((j, index) => {
    const diagramKey = diagramKeyForState(j.intent, j.stateType)
    // Only the innermost (actually active) journey has a "current step" to point at - a SUSPENDED
    // parent is parked waiting on its sub-journey, its own diagram has nothing to highlight.
    const isInnermost = index === journeys.length - 1
    const current = isInnermost && diagramKey ? CURRENT_STEP_BY_STATE_TYPE[diagramKey]?.[j.stateType] : undefined
    levels.push({
      label: index === 0 ? 'Journey' : 'SubJourney',
      detail: `${j.intent} · ${j.lifecycle} · ${j.stateType}`,
      hint: diagramKey ? (
        <DiagramTrigger spec={JOURNEY_DIAGRAMS[diagramKey]} current={current} label={t('Ablauf dieses Vorgangs als Diagramm anzeigen')} />
      ) : undefined,
    })
  })

  // Demo-only: why the innermost journey's state looks as it does (DemoStepReason), e.g. "only one
  // candidate available". It explains the journey's own decision, not the tool picked from it, so
  // it stays on the Journey/SubJourney box.
  const innermostNote = journeys?.at(-1)?.note
  if (levels.length > 0 && innermostNote) {
    levels[levels.length - 1] = { ...levels[levels.length - 1], note: resolveText(innermostNote) }
  }

  if (next?.type === 'tool') {
    levels.push({ label: 'Tool', detail: `${next.toolId} · ${next.step}` })
  } else if (next?.type === 'orchestrator') {
    // Not a real ToolSession, but just as much "the current step" as one - an orchestrator-owned
    // screen (a selection, a confirmation prompt) deserves the same visibility, not just a gap.
    levels.push({ label: 'Orchestrator', detail: `${next.context} · ${next.step}` })
  }

  return (
    <Disclosure summary={t('Struktur Einblicke')}>
      <div className="nesting-diagram">
        <div className="nesting-box nesting-box--1">
          <span className="nesting-label">
            Channel <em>{shorten(channelSessionId)} · {channelState ?? '-'}</em>
            <DiagramTrigger spec={JOURNEY_DIAGRAMS.channel} label={t('Lebenszyklus eines Channels als Diagramm anzeigen')} />
          </span>
          {nest(levels, 2)}
        </div>
      </div>
    </Disclosure>
  )
}
