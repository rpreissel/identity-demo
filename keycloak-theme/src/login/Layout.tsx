import { useState, type ReactNode } from 'react'
import type { KcContext } from './KcContext'
import { t } from '../texts'
import { DemoSlotContext } from './demoSlot'

/**
 * The page frame: white top bar saying this is Keycloak, the realm's name as the
 * large heading on the grey stage, one white card with the page title, messages and the form. Demo-only
 * helpers (DemoPersonPicker) go into the aside beside the card, never into it.
 */
export function Layout({ kcContext, title, children, info }: { kcContext: KcContext; title: ReactNode; children: ReactNode; info?: ReactNode }) {
  const { realm, message } = kcContext
  const [demoSlot, setDemoSlot] = useState<HTMLElement | null>(null)
  return (
    <div className="orc-page">
      <div className="orc-topbar">
        <p className="orc-note-header">{t('Sie sind jetzt bei Keycloak, dem Anmeldedienst dieser Website.')}</p>
      </div>
      <main className="orc-main">
        <p className="orc-realm">{realm.displayName || realm.name}</p>
        <div className="orc-stage">
          <aside className="orc-demo-aside" ref={setDemoSlot} aria-label={t('Demo')} />
          <DemoSlotContext.Provider value={demoSlot}>
            <section className="orc-card">
              <h1 className="orc-title">{title}</h1>
              {message && message.type !== 'success' && (
                <div className={`orc-alert orc-alert-${message.type}`} role={message.type === 'error' ? 'alert' : 'status'}>
                  {message.summary}
                </div>
              )}
              {children}
              {info && <div className="orc-info">{info}</div>}
            </section>
          </DemoSlotContext.Provider>
        </div>
      </main>
      {/* Which technique drew this page. */}
      <div className="orc-band">
        <p className="orc-made-with">{t('Erstellt mit {technik}', { technik: 'Keycloakify' })}</p>
      </div>
      <div className="orc-footer" />
    </div>
  )
}
