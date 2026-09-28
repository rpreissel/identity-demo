import type { ReactNode } from 'react'
import { resolveText, t } from '../texts'
import { Tx } from '../Tx'
import type { JourneyDebugStep } from '../types'
import { Disclosure } from './Disclosure'

interface StepExplanationProps {
  /** The running journey chain, outermost first - its purposes say why this step is here at all. */
  journeys?: JourneyDebugStep[]
  /** When no journey runs (start screen, logged in): why the screen looks the way it does. */
  idleReason?: string
  does: string
  actor: string
  /** The address of the step (tool/orchestrator, step) - for those who want to find it in the code. */
  technical?: string
  /** More on what happens, e.g. which methods this selection leaves out and why. */
  details?: ReactNode
  /** Which journey runs right now (named after its real intent) - heads the box. */
  journeyTitle?: string
  /** The diagram trigger for that journey, next to its name. */
  journeyDiagram?: ReactNode
}

/**
 * The demo column's answer to "what am I looking at?": why the orchestrator put this step here
 * (the journey's purpose, from the backend - DemoStepReason) in one sentence, then collapsed what
 * the step does and who is acting on it right now (from the tool module itself, ToolModule.explain).
 */
export function StepExplanation({ journeys, idleReason, does, actor, technical, details, journeyTitle, journeyDiagram }: StepExplanationProps) {
  const innermost = journeys?.at(-1)
  const outer = (journeys ?? []).slice(0, -1).filter((j) => j.purpose)
  const why = innermost?.purpose ? resolveText(innermost.purpose) : idleReason

  // Why this step is here leads; without a journey purpose, what it does takes that place.
  const lead = why ?? does

  // Two blocks: the demo column shows a step's own demo helpers between them (phone.css).
  return (
    <>
      <div className="step-explanation">
        {journeyTitle && (
          <p className="step-explanation__journey">
            <Tx text="Journey: {name}" name={<strong>{journeyTitle}</strong>} /> {journeyDiagram}
          </p>
        )}
        <p>
          {lead}
          {innermost?.note && <span className="step-explanation__note">{resolveText(innermost.note)}</span>}
          {/* A sub-journey runs for its parent - e.g. a step-up so a method may be removed. */}
          {outer.map((j) => (
            <span key={j.journeyId} className="step-explanation__note">
              {t('Übergeordnet: {zweck}', { zweck: resolveText(j.purpose) })}
            </span>
          ))}
        </p>
      </div>
      <div className="step-explanation__more">
        <Disclosure summary={t('Was passiert, wer ist dran?')}>
          <dl className="step-explanation__details">
            {why && (
              <>
                <dt>{t('Was passiert')}</dt>
                <dd>{does}</dd>
              </>
            )}
            {details && <dd>{details}</dd>}
            <dt>{t('Wer ist dran')}</dt>
            <dd>{actor}</dd>
          </dl>
          {technical && <p className="step-explanation__technical">{technical}</p>}
        </Disclosure>
      </div>
    </>
  )
}
