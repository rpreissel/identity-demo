import type { DemoPerson } from '../../types'
import { ValueThenCodeStep } from '../shared/ValueThenCodeStep'
import { SmsEnrollForm } from './SmsEnrollForm'
import { TanInputForm } from './TanInputForm'

/** enroll-sms after the code went out: the phone number, then the TAN ({@link ValueThenCodeStep}). */
export function SmsEnrollTanStep({ onSubmitTan, onSubmitNumber, error, demoTan, demoPersons, replaces }: {
  onSubmitTan: (tan: string) => void
  onSubmitNumber: (phoneNumber: string) => void
  error?: string
  demoTan?: string
  demoPersons?: DemoPerson[]
  replaces?: boolean
}) {
  return (
    <ValueThenCodeStep
      demoTan={demoTan}
      valueForm={<SmsEnrollForm onSubmit={onSubmitNumber} error={error} demoPersons={demoPersons} replaces={replaces} />}
      codeForm={<TanInputForm onSubmit={onSubmitTan} error={error} demoTan={demoTan} />}
    />
  )
}
