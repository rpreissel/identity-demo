import { t } from '../texts'
import type { MouseEvent, ReactNode } from 'react'
import { goToStart } from '../startWindow'
import { AREA_LINKS, areaLabel, type Area } from '../areas'

export interface NavTab<K extends string> {
  key: K
  label: string
}

interface Props<K extends string> {
  /** The area this page belongs to; its color carries the header. Omitted on the start page. */
  area?: Area | 'nect'
  /** The page's own views, in a row under the header. Omitted (or empty) for a page without. */
  tabs?: readonly NavTab<K>[]
  sub?: K
  onSelectTab?: (sub: K) => void
  /** Right-hand extras in the row of views (e.g. the admin page's logout). */
  actions?: ReactNode
}

function toStart(event: MouseEvent) {
  event.preventDefault()
  goToStart()
}

/**
 * The shell's header on every demo page ("Leitstand", docs/10-frontend.md): the wordmark back to the
 * start, the areas of the demo, the current one marked, all in the area's color (index.css). Under
 * it, if the page has any, its own views as tabs and its actions. Every area opens in its own named
 * tab (areas.ts); "Start" switches back to the tab the demo started in instead of loading it here.
 */
export function ChannelNav<K extends string>({ area, tabs = [], sub, onSelectTab, actions }: Props<K>) {
  return (
    <>
      {!area && (
        <div className="shell-legend" aria-hidden="true">
          {AREA_LINKS.map((link) => (
            <span key={link.key} className={`shell-legend--${link.key}`} />
          ))}
        </div>
      )}
      <header className="shell-header">
        <div className="shell-bar">
          <a className="shell-brand" href="/" onClick={toStart}>
            <svg width="34" height="34" viewBox="0 0 34 34" fill="none" aria-hidden="true">
              <rect className="shell-brand-mark" x="1" y="1" width="32" height="32" rx="9" />
              <path d="M10 22 L15 12 L19 19 L24 11" stroke="#ffffff" strokeWidth="2.4" strokeLinecap="round" strokeLinejoin="round" />
              <circle cx="24" cy="11" r="2.4" fill="#7fa2ff" />
            </svg>
            <span>Identity Journey</span>
          </a>
          <nav className="shell-areas" aria-label={t('Bereiche der Demo')}>
            <a className={area ? 'shell-area' : 'shell-area active'} href="/" onClick={toStart} aria-current={area ? undefined : 'page'}>
              {t('Start')}
            </a>
            {AREA_LINKS.map((link) => (
              <a
                key={link.key}
                className={link.key === area ? 'shell-area active' : 'shell-area'}
                href={link.href}
                target={link.target}
                aria-current={link.key === area ? 'page' : undefined}
              >
                <span className={`shell-dot shell-dot--${link.key}`} aria-hidden="true" />
                {areaLabel(link.key)}
              </a>
            ))}
          </nav>
        </div>
      </header>
      {(tabs.length > 0 || actions) && (
        <div className="shell-subbar">
          <div className="shell-subbar-inner">
            {tabs.length > 0 && (
              <div className="shell-tabs" role="tablist">
                {tabs.map((tab) => (
                  <button key={tab.key} role="tab" aria-selected={sub === tab.key} className={sub === tab.key ? 'active' : ''} onClick={() => onSelectTab?.(tab.key)}>
                    {tab.label}
                  </button>
                ))}
              </div>
            )}
            {actions && <div className="shell-actions">{actions}</div>}
          </div>
        </div>
      )}
    </>
  )
}
