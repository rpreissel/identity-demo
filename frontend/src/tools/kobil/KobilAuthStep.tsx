import { useState } from 'react'
import { KobilOtpStep } from './KobilOtpStep'
import { KobilUnlockGate } from './KobilUnlockGate'

interface KobilAuthStepProps {
  step: string
  tenantId?: string
  kobilUserId?: string
  kobilPin?: string
  unlockOptions: string[]
  onRelease: (unlock: Record<string, unknown>) => void
  onSubmitOtp: (body: Record<string, unknown>) => void
  error?: string
}

/**
 * Owns the one state the two auth-kobil screens share: whether to go back and unlock again. The
 * backend cannot answer that, since it already released a PIN and waits for the OTP. The screen
 * shown is otherwise derived: a PIN in hand means the SDK can run, no PIN means another unlock.
 */
export function KobilAuthStep({
  step,
  tenantId,
  kobilUserId,
  kobilPin,
  unlockOptions,
  onRelease,
  onSubmitOtp,
  error,
}: KobilAuthStepProps) {
  const [relocked, setRelocked] = useState(false)

  const showOtp = step === 'otp' && kobilPin !== undefined && !relocked
  if (showOtp) {
    return (
      <KobilOtpStep
        tenantId={tenantId}
        kobilUserId={kobilUserId}
        kobilPin={kobilPin}
        onSubmit={onSubmitOtp}
        onBackToUnlock={() => setRelocked(true)}
        error={error}
      />
    )
  }

  return (
    <KobilUnlockGate
      kobilUserId={kobilUserId}
      options={unlockOptions}
      onRelease={(unlock) => {
        setRelocked(false)
        onRelease(unlock)
      }}
      error={error}
    />
  )
}
