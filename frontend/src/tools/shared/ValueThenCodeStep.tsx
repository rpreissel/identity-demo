import { useEffect, useState, type ReactNode } from 'react'
import { useInnerBack } from '../../components/InnerBack'

/**
 * An enrollment after the code went out. "Zurück" shows the value screen (number or address) again -
 * a pure screen change. Sending a value from there is the process step: the tool accepts a new value
 * in every state and sends a new code, which brings the code screen back.
 */
export function ValueThenCodeStep({ demoTan, valueForm, codeForm }: {
  /** Changes with every issued code; a new one means the value was accepted. */
  demoTan?: string
  valueForm: ReactNode
  codeForm: ReactNode
}) {
  const [editingValue, setEditingValue] = useState(false)
  useInnerBack(editingValue ? null : () => setEditingValue(true))

  // A new code arrived: the value was accepted, so the code screen is next.
  useEffect(() => setEditingValue(false), [demoTan])

  return editingValue ? valueForm : codeForm
}
