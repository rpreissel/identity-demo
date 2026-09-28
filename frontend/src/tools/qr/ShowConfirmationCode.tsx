import { t } from '../../texts'
import { StepActions } from '../../components/PhoneFrame'
import { CodeDisplay } from '../../components/CodeDisplay'

interface ShowConfirmationCodeProps {
  /** Only in the response to the approval - the server keeps just a hash, so after a reload it is gone. */
  confirmationCode?: string
  onDone: () => void
  error?: string
}

/**
 * `confirm-qr-login`'s `showCode` step: the code the user types into the browser that is waiting.
 * It must never be passed on - whoever types it into their browser is logged in as this account.
 */
export function ShowConfirmationCode({ confirmationCode, onDone, error }: ShowConfirmationCodeProps) {
  return (
    <div className="card">
      <h2>{t('Code im Browser eingeben')}</h2>
      {confirmationCode ? (
        <>
          <p>{t('Geben Sie diesen Code in dem Browser ein, in dem Sie sich gerade anmelden.')}</p>
          <CodeDisplay code={confirmationCode} label={t('Code')} />
          <p className="hint">{t('Geben Sie diesen Code nur in das Browserfenster ein, das Sie gerade selbst vor sich haben. Geben Sie ihn niemals weiter - auch nicht auf Nachfrage.')}</p>
        </>
      ) : (
        <p className="hint">{t('Der Code wird nur einmal angezeigt. Ist er verloren, starten Sie die Anmeldung per QR-Code neu.')}</p>
      )}
      {error && <div className="hint">{error}</div>}
      <StepActions>
        <button onClick={onDone}>{t('Fertig')}</button>
      </StepActions>
    </div>
  )
}
