import type { DemoPerson } from '../../types'
import { ValueThenCodeStep } from '../shared/ValueThenCodeStep'
import { EmailCodeInputForm } from './EmailCodeInputForm'
import { EmailEnrollForm } from './EmailEnrollForm'

/** confirm-email after the code went out: the address, then the code ({@link ValueThenCodeStep}). */
export function ConfirmEmailCodeStep({ onSubmitCode, onSubmitAddress, error, demoTan, demoPersons }: {
  onSubmitCode: (code: string) => void
  onSubmitAddress: (email: string) => void
  error?: string
  demoTan?: string
  demoPersons?: DemoPerson[]
}) {
  return (
    <ValueThenCodeStep
      demoTan={demoTan}
      valueForm={<EmailEnrollForm onSubmit={onSubmitAddress} error={error} demoPersons={demoPersons} />}
      codeForm={<EmailCodeInputForm onSubmit={onSubmitCode} error={error} demoTan={demoTan} />}
    />
  )
}
