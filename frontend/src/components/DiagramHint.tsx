import type { ReactNode } from 'react'
import { JourneyDiagram, type JourneyDiagramCurrentStep, type JourneyDiagramSpec } from './JourneyDiagram'

interface DiagramHintProps {
  spec: JourneyDiagramSpec
  children: ReactNode
  /** For wrapping a small inline trigger (DiagramTrigger) instead of a full-width block. */
  inline?: boolean
  /** Highlights the box a running instance is at. Omitted for the static hover previews. */
  current?: JourneyDiagramCurrentStep
  /**
   * Opens the popover below the trigger instead of above. For triggers near the top of the page
   * (the journey-context banner, the entry screen's list): an upward popover would render above
   * y=0, where no scrolling reaches it.
   */
  openDown?: boolean
}

/**
 * Wraps anything (a button, a status line) with a hover/focus-revealed preview of a journey's
 * shape. The diagram stays out of the way until someone wants it.
 */
export function DiagramHint({ spec, children, inline, current, openDown }: DiagramHintProps) {
  return (
    <span className={`diagram-hint${inline ? ' diagram-hint--inline' : ' diagram-hint--block'}`}>
      {children}
      <span className={`diagram-hint-popover${openDown ? ' diagram-hint-popover--down' : ''}`}>
        <JourneyDiagram {...spec} current={current} />
      </span>
    </span>
  )
}

/**
 * The one trigger for a diagram preview: a small info icon, named for screen readers. Drawn as
 * SVG so it looks the same everywhere instead of each platform's emoji.
 */
export function DiagramTrigger({
  spec,
  label,
  current,
  openDown,
}: {
  spec: JourneyDiagramSpec
  label: string
  current?: JourneyDiagramCurrentStep
  openDown?: boolean
}) {
  return (
    <DiagramHint spec={spec} current={current} inline openDown={openDown}>
      <span className="diagram-hint-trigger" tabIndex={0} aria-label={label}>
        <svg viewBox="0 0 16 16" width="16" height="16" aria-hidden="true">
          <circle cx="8" cy="8" r="7" fill="none" stroke="currentColor" strokeWidth="1.5" />
          <path d="M8 7.2v4.3M8 4.7v.1" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
        </svg>
      </span>
    </DiagramHint>
  )
}
