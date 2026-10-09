import type { ReactNode } from 'react'
import type { KcContext } from '../KcContext'
import { Layout } from '../Layout'
import { t } from '../../texts'

/**
 * The frame every tool page shares: title, hint, one form posting to
 * Keycloak, and "Weiter" next to a way out that skips field validation: "Zurück" (orchestrator_back,
 * back to the selection) or, with `cancel`, "Abbrechen" (orchestrator_abandon). With `onBack`,
 * "Zurück" stays on the page.
 */
export function ToolForm({
  kcContext,
  title,
  hint,
  children,
  submitLabel,
  cancel = false,
  onBack,
}: {
  kcContext: KcContext
  title: string
  hint?: ReactNode
  children?: ReactNode
  /** Default "Weiter"; null for a page without its own submit (waiting for the app). */
  submitLabel?: string | null
  /** "Abbrechen" (decline the tool) instead of "Zurück" (back to the selection). */
  cancel?: boolean
  /** "Zurück" handled on this page instead of leaving the tool. */
  onBack?: () => void
}) {
  return (
    <Layout kcContext={kcContext} title={title}>
      {hint && <p className="orc-subtitle">{hint}</p>}
      <form id="kc-orchestrator-tool-form" action={kcContext.url.loginAction} method="post">
        {children}
        <div className="orc-actions">
          {submitLabel !== null && (
            <button className="orc-button orc-button-primary" type="submit">
              {submitLabel ?? t('Weiter')}
            </button>
          )}
          {onBack ? (
            <button className="orc-button" type="button" onClick={onBack}>
              {t('Zurück')}
            </button>
          ) : cancel ? (
            <button className="orc-button" type="submit" name="orchestrator_abandon" value="true" formNoValidate>
              {t('Abbrechen')}
            </button>
          ) : (
            <button className="orc-button" type="submit" name="orchestrator_back" value="true" formNoValidate>
              {t('Zurück')}
            </button>
          )}
        </div>
      </form>
    </Layout>
  )
}
