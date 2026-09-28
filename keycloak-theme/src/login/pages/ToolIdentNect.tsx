import type { PageContext } from '../KcContext'
import { Layout } from '../Layout'
import { t } from '../../texts'

/**
 * `tool-ident-nect.ftl`: a link takes the user to Nect's jump page; Nect sends them back to this
 * step's own action URL, which Keycloak treats like this form's post. Without `jumpUrl` the last
 * attempt failed and "Erneut versuchen" opens a fresh case. "Zurück" leaves the tool.
 */
export function ToolIdentNect({ kcContext }: { kcContext: PageContext<'tool-ident-nect.ftl'> }) {
  const { pageTitle: title, hint, jumpUrl } = kcContext
  return (
    <Layout kcContext={kcContext} title={title}>
      {hint && <p className="orc-subtitle">{hint}</p>}
      <p className="orc-hint">
        {t(
          'Sie wechseln zu Nect und weisen sich dort mit Personalausweis, Reisepass oder EUDI-Wallet aus. Danach kommen Sie automatisch hierher zurück.',
        )}
      </p>
      <form id="kc-orchestrator-tool-form" action={kcContext.url.loginAction} method="post">
        <div className="orc-actions">
          {jumpUrl ? (
            <a id="nect-jump" className="orc-button orc-button-primary" href={jumpUrl}>
              {t('Weiter zu Nect')}
            </a>
          ) : (
            <button className="orc-button orc-button-primary" type="submit" name="retry" value="true">
              {t('Erneut versuchen')}
            </button>
          )}
          <button className="orc-button" type="submit" name="orchestrator_back" value="true" formNoValidate>
            {t('Zurück')}
          </button>
        </div>
      </form>
    </Layout>
  )
}
