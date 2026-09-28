import { useEffect, useState } from 'react'
import type { DemoPerson } from '../../types'
import { useInnerBack } from '../../components/InnerBack'
import { SmsEnrollForm } from './SmsEnrollForm'
import { TanInputForm } from './TanInputForm'

/**
 * enroll-sms after the code went out. "Zurück" shows the phone number screen again - a pure screen
 * change. Sending a number from there is the process step: enroll-sms accepts a new number in every
 * state and sends a new code, which brings the code screen back.
 */
export function SmsEnrollTanStep({
  onSubmitTan,
  onSubmitNumber,
  error,
  demoTan,
  demoPersons,
}: {
  onSubmitTan: (tan: string) => void
  onSubmitNumber: (phoneNumber: string) => void
  error?: string
  demoTan?: string
  demoPersons?: DemoPerson[]
}) {
  const [editingNumber, setEditingNumber] = useState(false)
  useInnerBack(editingNumber ? null : () => setEditingNumber(true))

  // A new code arrived: the number was accepted, so the code screen is next.
  useEffect(() => setEditingNumber(false), [demoTan])

  return editingNumber ? (
    <SmsEnrollForm onSubmit={onSubmitNumber} error={error} demoPersons={demoPersons} />
  ) : (
    <TanInputForm onSubmit={onSubmitTan} error={error} demoTan={demoTan} />
  )
}
