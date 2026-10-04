import { useEffect } from 'react'
import type { PageContext } from '../KcContext'
import { Field } from '../components/Field'
import { ToolForm } from '../components/ToolForm'
import { t } from '../../texts'
import { checkQrStatus, pollQrStatus } from '../qrStatusPoll'

/**
 * `tool-qr-wait.ftl`, two steps. `waitForApp`: QR code and pairing code (typing it is an equal way
 * in); the page asks `statusUrl` in the background and posts its form only once something changed,
 * so the code stays put (ADR-45). `enterCode`: only typing the app's confirmation code here logs
 * this browser in.
 */
export function ToolQrWait({ kcContext }: { kcContext: PageContext<'tool-qr-wait.ftl'> }) {
  if (kcContext.step === 'enterCode') {
    const { pageTitle: title, hint } = kcContext
    return (
      <ToolForm kcContext={kcContext} title={title} hint={hint} cancel>
        <Field
          id="confirmationCode"
          label={t('Code aus der App')}
          inputMode="numeric"
          autoComplete="one-time-code"
          autoFocus
          hint={t('Ihre App zeigt nach der Freigabe einen sechsstelligen Code. Geben Sie ihn hier ein.')}
        />
      </ToolForm>
    )
  }
  return <WaitForApp kcContext={kcContext} />
}

function WaitForApp({ kcContext }: { kcContext: Extract<PageContext<'tool-qr-wait.ftl'>, { step: 'waitForApp' }> }) {
  const { pageTitle: title, hint, pairingCode, deepLink, qrDataUri, statusUrl } = kcContext

  useEffect(() => {
    const form = document.getElementById('kc-orchestrator-tool-form') as HTMLFormElement | null
    if (!form) return
    const stop = pollQrStatus(() => checkQrStatus(statusUrl), () => form.submit())
    // "Abbrechen" posts the form itself; no status answer may post it a second time.
    form.addEventListener('submit', stop)
    return () => {
      stop()
      form.removeEventListener('submit', stop)
    }
  }, [statusUrl])

  return (
    <ToolForm kcContext={kcContext} title={title} hint={hint} submitLabel={null} cancel>
      <div className="orc-qr">
        <img src={qrDataUri} alt={t('QR-Code')} width={220} height={220} />
        <p>
          {t('Pairing-Code')}: <strong className="orc-qr-code">{pairingCode}</strong>
        </p>
        <p className="orc-hint">{t('Nach der Freigabe zeigt Ihre App einen Code, den Sie hier eingeben.')}</p>
        {/* Named target: a click must not navigate this waiting page away (docs/10-frontend.md #6). */}
        <a href={deepLink} target="identity-demo-app-kanal">
          {deepLink}
        </a>
        <p className="orc-hint">{t('Demo-Link: öffnet die App direkt (ohne Kamera) mit vorbefülltem Pairing-Code.')}</p>
      </div>
    </ToolForm>
  )
}
