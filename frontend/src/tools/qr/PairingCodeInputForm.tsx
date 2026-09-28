import { useState } from 'react'
import { forgetPendingPairingCode, loadPendingPairingCode } from '../../session'
import { t } from '../../texts'
import { StepActions } from '../../components/PhoneFrame'

interface PairingCodeInputFormProps {
  onSubmit: (pairingCode: string) => void
  error?: string
}

/**
 * `confirm-qr-login`'s `input` step (docs/05-api.md, Peer-Login bestätigen): the pairing code is
 * scanned or typed here, or pre-filled from the WEB channel's demo link (session.ts). Forgotten
 * once submitted, so a later confirm-qr-login run never reuses a stale code. A code known at
 * activation skips this step server-side (AppChannelApp.tsx).
 */
export function PairingCodeInputForm({ onSubmit, error }: PairingCodeInputFormProps) {
  const [pairingCode, setPairingCode] = useState(() => loadPendingPairingCode() ?? '')

  function handleSubmit(event: React.FormEvent) {
    event.preventDefault()
    forgetPendingPairingCode()
    // The stored/displayed form may include a grouping dash (docs/07-betrieb.md #5,
    // "XXXX-XXXX") - the actual pairingCode value never contains one.
    onSubmit(pairingCode.replace(/[^a-zA-Z0-9]/g, '').toUpperCase())
  }

  return (
    <div className="card">
      <h2>{t('Web-Login per QR bestätigen')}</h2>
      <p>{t('Geben Sie den Pairing-Code von der Web-Seite ein, oder scannen Sie deren QR-Code.')}</p>
      {error && <div className="hint">{error}</div>}
      <form id="pairing-code" onSubmit={handleSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
        <div className="form-group">
          <label htmlFor="pairingCode">{t('Pairing-Code')}</label>
          <input
            id="pairingCode"
            className="code-input"
            value={pairingCode}
            onChange={(e) => setPairingCode(e.target.value)}
            placeholder={t('z. B. {beispiel}', { beispiel: 'AB3D-7KQ2' })}
            required
            autoFocus
          />
        </div>
        <StepActions>
          <button type="submit" form="pairing-code">{t('Weiter')}</button>
        </StepActions>
      </form>
    </div>
  )
}
