import { t } from '../../texts'
import { Tx } from '../../Tx'
import { DemoNote } from '../../components/DemoArea'
import { StepActions } from '../../components/PhoneFrame'

interface DeviceAccessGateProps {
  onConfirm: (userVerification: 'pin' | 'biometric') => void
  busy?: boolean
}

/**
 * Mocks the system PIN/biometric prompt that gates use of the device's private key (a platform
 * authenticator's user verification). Biometric first with a PIN fallback, like Face ID/Touch ID.
 * Which one was used is decided here, per attempt, not at enrollment (docs/03-tool-architektur.md):
 * WebAuthn never tells the relying party the modality either.
 */
export function DeviceAccessGate({ onConfirm, busy }: DeviceAccessGateProps) {
  return (
    <div className="card">
      <h2>{t('Gerät entsperren')}</h2>
      <p>{t('Bestätigen Sie den Zugriff auf den geräteeigenen Schlüssel.')}</p>
      <DemoNote>
        <Tx text="{modus} PIN/Biometrie werden hier nur simuliert, keine echte Systemabfrage." modus={<strong>{t('Demo-Modus:')}</strong>} />
      </DemoNote>
      <StepActions>
        <button type="button" disabled={busy} onClick={() => onConfirm('biometric')}>
          {t('Mit Biometrie bestätigen')}
        </button>
        <button type="button" className="secondary" disabled={busy} onClick={() => onConfirm('pin')}>
          {t('Stattdessen PIN verwenden')}
        </button>
      </StepActions>
    </div>
  )
}
