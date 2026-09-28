import { useEffect, useState } from 'react'
import type { DemoPerson } from '../../types'
import { useInnerBack } from '../../components/InnerBack'
import { EmailCodeInputForm } from './EmailCodeInputForm'
import { EmailEnrollForm } from './EmailEnrollForm'

/**
 * confirm-email after the code went out. "Zurück" shows the address screen again - a pure screen
 * change. Sending an address from there is the process step: confirm-email accepts a new address
 * in every state and issues a new code, which brings the code screen back.
 */
export function ConfirmEmailCodeStep({
  onSubmitCode,
  onSubmitAddress,
  error,
  demoTan,
  demoPersons,
}: {
  onSubmitCode: (code: string) => void
  onSubmitAddress: (email: string) => void
  error?: string
  demoTan?: string
  demoPersons?: DemoPerson[]
}) {
  const [editingAddress, setEditingAddress] = useState(false)
  useInnerBack(editingAddress ? null : () => setEditingAddress(true))

  // A new code arrived: the address was accepted, so the code screen is next.
  useEffect(() => setEditingAddress(false), [demoTan])

  return editingAddress ? (
    <EmailEnrollForm onSubmit={onSubmitAddress} error={error} demoPersons={demoPersons} />
  ) : (
    <EmailCodeInputForm onSubmit={onSubmitCode} error={error} demoTan={demoTan} />
  )
}
