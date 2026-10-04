import { useState } from 'react'
import { createDeviceProof, getOrCreateDeviceKeyPair } from '../../deviceKey.ts'
import { DeviceAccessGate } from './DeviceAccessGate'
import { t } from '../../texts'
import { toolSessionPath } from '../../api'
import { StepActions } from '../../components/PhoneFrame'

interface DeviceEnrollFormProps {
  toolSessionId: string
  toolId: string
  onSubmit: (body: Record<string, unknown>) => void
  error?: string
}

/**
 * enroll-device/enroll step - registers this device's key pair as a new loa2-capable credential.
 * Asks for a display name first (e.g. "Laptop", "Handy") - several devices can each hold their
 * own active credential (docs/03-tool-architektur.md, allowsMultipleInstances), so a name is what
 * lets the user tell them apart later under "Methoden verwalten".
 */
export function DeviceEnrollForm({ toolSessionId, toolId, onSubmit, error }: DeviceEnrollFormProps) {
  const [label, setLabel] = useState('')
  const [confirmingName, setConfirmingName] = useState(true)
  const [busy, setBusy] = useState(false)

  function handleNameSubmit(event: React.FormEvent) {
    event.preventDefault()
    setConfirmingName(false)
  }

  async function handleConfirm(userVerification: 'pin' | 'biometric') {
    setBusy(true)
    try {
      const { keyPair } = await getOrCreateDeviceKeyPair()
      const htu = `${window.location.origin}${toolSessionPath(toolSessionId, toolId)}`
      const deviceProof = await createDeviceProof(keyPair, 'PATCH', htu, userVerification)
      onSubmit({ deviceProof, label: label.trim() || t('Mein Gerät') })
    } finally {
      setBusy(false)
    }
  }

  if (confirmingName) {
    return (
      <div className="card">
        <h2>{t('Gerät benennen')}</h2>
        <p>{t('Vergeben Sie einen Namen, um dieses Gerät später wiederzuerkennen (z.\u00A0B. „Laptop“, „Handy“).')}</p>
        {error && <div className="hint">{error}</div>}
        <form id="device-enroll" onSubmit={handleNameSubmit} className="form-grid" style={{ marginTop: '1rem' }}>
          <div className="form-group">
            <label htmlFor="device-label">{t('Gerätename')}</label>
            <input
              id="device-label"
              value={label}
              onChange={(e) => setLabel(e.target.value)}
              placeholder={t('Mein Gerät')}
              autoFocus
            />
          </div>
          <StepActions>
            <button type="submit" form="device-enroll">{t('Weiter')}</button>
          </StepActions>
        </form>
      </div>
    )
  }

  return (
    <>
      {error && <div className="hint">{error}</div>}
      <DeviceAccessGate onConfirm={handleConfirm} busy={busy} />
    </>
  )
}
