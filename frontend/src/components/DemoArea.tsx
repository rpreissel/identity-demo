import { t } from '../texts'
import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { Disclosure } from './Disclosure'

/**
 * The App channel's split (docs/10-frontend.md): the smartphone shows only what a real app shows,
 * everything that exists only for the demo sits next to it. A demo helper of one screen (a form's
 * test person picker, a TAN step's demo code) is written inside that screen's component, wrapped
 * in <Demo>. A portal renders it in the demo column, still in its form's React tree, and it
 * disappears with its screen.
 */
interface DemoTargets {
  step: HTMLElement | null
  background: HTMLElement | null
}

/** undefined: no demo column at all (tests, other channels) - demo helpers stay where they are written. */
const DemoTarget = createContext<DemoTargets | undefined>(undefined)

export interface DemoTargetRefs {
  step: (el: HTMLElement | null) => void
  background: (el: HTMLElement | null) => void
}

export function DemoProvider({ children }: { children: (targets: DemoTargetRefs) => ReactNode }) {
  const [step, setStep] = useState<HTMLElement | null>(null)
  const [background, setBackground] = useState<HTMLElement | null>(null)
  return <DemoTarget.Provider value={{ step, background }}>{children({ step: setStep, background: setBackground })}</DemoTarget.Provider>
}

/**
 * Renders [children] in the demo column: in "Zu diesem Schritt", or with [background] under
 * "Abläufe hinter den Knöpfen". Without a demo column (tests, other channels) they stay where they
 * are written; with one, nothing shows until its slot exists.
 */
export function Demo({ children, background = false }: { children: ReactNode; background?: boolean }) {
  const targets = useContext(DemoTarget)
  if (targets === undefined) return <>{children}</>
  const target = background ? targets.background : targets.step
  return target ? createPortal(<div className="demo-slot">{children}</div>, target) : null
}

/** A demo-only note (what is simulated, where a code comes from), shown in the demo column. */
export function DemoNote({ children }: { children: ReactNode }) {
  return (
    <Demo>
      <p className="demo-note">{children}</p>
    </Demo>
  )
}

/**
 * What a page's first-time visitor should read, open only on the first visit in this browser.
 * Storage may be unavailable (private window, blocked site data); then it simply stays closed.
 */
function useFirstVisit(key: string): boolean {
  const [first] = useState(() => {
    try {
      return localStorage.getItem(key) === null
    } catch {
      return false
    }
  })
  useEffect(() => {
    try {
      localStorage.setItem(key, 'seen')
    } catch {
      // Nothing to remember then - the intro opens again next time.
    }
  }, [key])
  return first
}

export interface DemoIntro {
  /** Remembers per browser that this intro was seen. */
  id: string
  title: string
  body: ReactNode
}

/**
 * The demo column, ordered by what a visitor asks first: whose session this is, what this step is
 * about (the slot <Demo> portals into), what the demo lets you do, then the background - all
 * collapsed, the intro open only on the first visit (docs/10-frontend.md).
 */
export function DemoArea({
  targets,
  session,
  actions,
  intro,
  background,
  children,
}: {
  targets: DemoTargetRefs
  /** Whose session this is, at which level - always first, on every screen (SessionSummary). */
  session?: ReactNode
  /** Demo-only ways to act (resume, forget, restart), as DemoAction rows. */
  actions?: ReactNode
  intro?: DemoIntro
  /** Further collapsed sections under "Hintergrund". */
  background?: ReactNode
  /** Page-specific cards between the actions and the background. */
  children?: ReactNode
}) {
  const introOpen = useFirstVisit(`identity-demo-intro-seen-${intro?.id ?? 'none'}`)
  return (
    <aside className="demo-area" aria-label={t('Demo-Werkzeuge')}>
      {session}
      <section className="card demo-step">
        <h2>{t('Zu diesem Schritt')}</h2>
        <div ref={targets.step} className="demo-step__slot" />
      </section>
      {actions && (
        <section className="card">
          <h2>{t('Aktionen der Demo')}</h2>
          <ul className="task-list">{actions}</ul>
        </section>
      )}
      {children}
      <section className="card demo-background">
        <h2>{t('Hintergrund')}</h2>
        {intro && (
          <Disclosure summary={intro.title} defaultOpen={introOpen}>
            {intro.body}
          </Disclosure>
        )}
        <div className="demo-background__diagrams">
          <Disclosure summary={t('Abläufe hinter den Knöpfen')}>
            <div ref={targets.background} />
          </Disclosure>
        </div>
        {background}
      </section>
    </aside>
  )
}

/** One demo action: what it does, and its button. */
export function DemoAction({ text, label, onClick, ariaLabel }: { text: string; label: string; onClick: () => void; ariaLabel?: string }) {
  return (
    <li className="task-row">
      <span>{text}</span>
      <button className="secondary" onClick={onClick} aria-label={ariaLabel}>
        {label}
      </button>
    </li>
  )
}
