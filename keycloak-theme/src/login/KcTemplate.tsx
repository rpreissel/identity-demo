import { useEffect } from 'react'
import type { TemplateProps } from 'keycloakify/login/TemplateProps'
import type { KcContext } from './KcContext'
import type { I18n } from './i18n'
import { Layout } from './Layout'
import { t } from '../texts'

/**
 * The frame for Keycloak's own pages (info, error, expired page ...) - our Layout
 * instead of Keycloakify's PatternFly template, so they look like the orchestrator's pages
 * (docs/adr/ADR-057-keycloakify-einziges-login-theme.md). When the login already knows who signs
 * in (step-up, re-authentication) by its e-mail address, the title stays and a hint under it names
 * it, with a way to start over - like the hint on the orchestrator's tool pages. Keycloak's template would put
 * the address in place of the title.
 */
export default function KcTemplate({
  kcContext,
  i18n,
  children,
  headerNode,
  displayInfo = false,
  infoNode = null,
  socialProvidersNode = null,
  documentTitle,
}: TemplateProps<KcContext, I18n>) {
  const { realm, auth, url } = kcContext
  const { msg, msgStr } = i18n

  useEffect(() => {
    document.title = documentTitle ?? msgStr('loginTitle', realm.displayName || realm.name)
  }, []) // eslint-disable-line react-hooks/exhaustive-deps

  // Only an address says something; an account's username is technical (`account-<id>`, ADR-46).
  const knownUser =
    auth !== undefined && auth.showUsername && !auth.showResetCredentials && auth.attemptedUsername?.includes('@') ? auth.attemptedUsername : undefined
  return (
    <Layout kcContext={kcContext} title={headerNode} info={displayInfo ? infoNode : undefined}>
      {knownUser && (
        <p className="orc-subtitle">
          {t('Für {konto}.', { konto: knownUser })} <a href={url.loginRestartFlowUrl}>{t('Nicht Sie?')}</a>
        </p>
      )}
      <div className="orc-kc">{children}</div>
      {auth?.showTryAnotherWayLink && (
        <form id="kc-select-try-another-way-form" action={url.loginAction} method="post">
          <input type="hidden" name="tryAnotherWay" value="on" />
          <button type="submit" className="orc-link-button">
            {msg('doTryAnotherWay')}
          </button>
        </form>
      )}
      {socialProvidersNode}
    </Layout>
  )
}
