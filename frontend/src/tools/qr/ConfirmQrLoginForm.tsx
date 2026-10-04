import { t } from '../../texts'
import { StepActions } from '../../components/PhoneFrame'

interface ConfirmQrLoginFormProps {
  onAccept: () => void
  onReject: () => void
  error?: string
}

/**
 * `approve-qr`'s `confirm` step (docs/verfahren/qr.md).
 * Approving does not log the browser in by itself: the app then shows a code that has to be typed
 * into the browser - so approving a request you did not start yourself hands nothing over.
 */
export function ConfirmQrLoginForm({ onAccept, onReject, error }: ConfirmQrLoginFormProps) {
  return (
    <div className="card">
      <h2>{t('Web-Login bestätigen?')}</h2>
      <p>{t('Ein Browser möchte sich mit Ihrem Konto anmelden. Wenn Sie bestätigen, zeigt die App einen Code, den Sie dort eingeben.')}</p>
      <p className="hint">{t('Bestätigen Sie nur, wenn Sie die Anmeldung gerade selbst im Browser begonnen haben.')}</p>
      {error && <div className="hint">{error}</div>}
      <StepActions>
        <button onClick={onAccept}>{t('Bestätigen')}</button>
        <button className="secondary" onClick={onReject}>
          {t('Ablehnen')}
        </button>
      </StepActions>
    </div>
  )
}
