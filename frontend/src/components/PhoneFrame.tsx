import { createContext, useContext, useEffect, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { language } from '../texts'
import { LanguageSwitch } from './LanguageSwitch'

interface PhoneTargets {
  bar: HTMLElement | null
  nav: HTMLElement | null
}

/** undefined: no phone frame around (tests, other pages) - actions stay where they are written. */
const PhoneTargetsContext = createContext<PhoneTargets | undefined>(undefined)

/**
 * An implied smartphone: a rounded frame with a status bar, the app's dark blue head, a white
 * sheet that scrolls at phone width and a fixed action bar at the bottom. Only what the real app
 * would show goes in here (DemoArea). The look follows a health insurer's app - head, sheet, soft
 * cards, list rows with a chevron - without any brand (phone.css).
 *
 * Every screen has the same places (docs/10-frontend.md, "Aufbau eines Bildschirms"): the ways out
 * (<StepNav>) in a quiet row on top of the sheet, the screen's own action and at most one second
 * one (<StepActions>) in the bar. [footer] takes the bar's place while it is shown, for a question
 * about leaving the screen.
 */
export function PhoneFrame({ title, footer, children }: { title: string; footer?: ReactNode; children: ReactNode }) {
  const [bar, setBar] = useState<HTMLElement | null>(null)
  const [nav, setNav] = useState<HTMLElement | null>(null)
  return (
    <div className="phone">
      <div className="phone__screen">
        <div className="phone__status" aria-hidden="true">
          <PhoneClock />
          <span className="phone__status-icons">
            <i className="phone__signal" />
            <i className="phone__battery" />
          </span>
        </div>
        <header className="phone__head">
          <span className="phone__title">{title}</span>
          <LanguageSwitch className="phone__languages" buttonClassName="phone__language" />
        </header>
        <PhoneTargetsContext.Provider value={{ bar, nav }}>
          <div className="phone__sheet">
            <div ref={setNav} className="phone__nav" />
            {children}
          </div>
        </PhoneTargetsContext.Provider>
        <div className={footer ? 'phone__bar phone__bar--footer' : 'phone__bar'}>
          <div ref={setBar} className="phone__bar-actions" />
          {footer}
        </div>
        <div className="phone__home" aria-hidden="true" />
      </div>
    </div>
  )
}

/**
 * A screen's main action and at most one second action, rendered in the phone's action bar -
 * still in the screen's React tree, gone with the screen. A submit button there is outside its
 * <form>, so it names the form by id (`form={formId}`). The first button is the main one.
 */
export function StepActions({ children }: { children: ReactNode }) {
  const target = useContext(PhoneTargetsContext)
  const actions = <div className="form-actions">{children}</div>
  if (target === undefined) return actions
  return target.bar ? createPortal(actions, target.bar) : null
}

/**
 * The ways out of a screen (Zurück, Abbrechen), rendered in the quiet row on top of the sheet.
 * A button with the class `back` sits on the left with a chevron, every other one on the right.
 */
export function StepNav({ children }: { children: ReactNode }) {
  const target = useContext(PhoneTargetsContext)
  const links = <div className="phone__nav-links">{children}</div>
  if (target === undefined) return links
  return target.nav ? createPortal(links, target.nav) : null
}

/** The status bar's clock: the real time, moving on at each full minute like a phone's. */
function PhoneClock() {
  const [now, setNow] = useState(() => new Date())
  useEffect(() => {
    let interval: ReturnType<typeof setInterval> | undefined
    // First tick at the next full minute, then every minute - so it changes when the phone's would.
    const timeout = setTimeout(() => {
      setNow(new Date())
      interval = setInterval(() => setNow(new Date()), 60_000)
    }, 60_000 - (Date.now() % 60_000))
    return () => {
      clearTimeout(timeout)
      if (interval) clearInterval(interval)
    }
  }, [])
  return <span>{now.toLocaleTimeString(language(), { hour: 'numeric', minute: '2-digit' })}</span>
}
