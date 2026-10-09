import type { ReactNode } from 'react'

/**
 * The striped band under the shell's header on every page that plays a foreign system (register,
 * mailbox, Nect): a label that says so at a glance, and a sentence on how our application uses it.
 */
export function SimBand({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="sim-band">
      <div className="sim-band-inner">
        <span className="sim-band-label">{label}</span>
        <span className="sim-band-text">{children}</span>
      </div>
    </div>
  )
}

/** Above a page's content: the area's name small, the view's name large. */
export function AreaHead({ kicker, title, children }: { kicker: string; title: string; children?: ReactNode }) {
  return (
    <div className="area-head">
      <div>
        <span className="area-kicker">{kicker}</span>
        <h1>{title}</h1>
      </div>
      {children}
    </div>
  )
}
