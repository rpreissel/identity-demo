import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { SmsEnrollForm } from './SmsEnrollForm'

const max = { personId: 'P000000001', givenNames: 'Max', familyName: 'Muster', email: 'max.mustermann@example.com', phoneNumber: '+49 170 0000001' }
const erika = { personId: 'P000000002', givenNames: 'Erika', familyName: 'Beispiel', email: 'erika.beispiel@example.com', phoneNumber: '+49 170 0000002' }

function field(label: string): HTMLInputElement {
  return screen.getByLabelText(label) as HTMLInputElement
}

describe('SmsEnrollForm', () => {
  it('prefills the first test person’s mobile number from the register', () => {
    render(<SmsEnrollForm onSubmit={vi.fn()} demoPersons={[max, erika]} />)

    expect(field('Telefonnummer').value).toBe('+49 170 0000001')
  })

  it('takes the number of the test person picked', () => {
    const onSubmit = vi.fn()
    render(<SmsEnrollForm onSubmit={onSubmit} demoPersons={[max, erika]} />)
    fireEvent.change(screen.getByLabelText(/Testperson übernehmen/), { target: { value: 'P000000002' } })

    fireEvent.click(screen.getByRole('button', { name: 'Code senden' }))

    expect(onSubmit).toHaveBeenCalledWith('+49 170 0000002')
  })

  it('says that the number is changed when the step replaces an active one', () => {
    render(<SmsEnrollForm onSubmit={vi.fn()} replaces />)

    expect(screen.getByRole('heading', { name: 'Telefonnummer ändern' })).toBeInTheDocument()
    expect(field('Neue Telefonnummer').value).toBe('')
  })

  it('starts empty without demo values (ADR-28)', () => {
    render(<SmsEnrollForm onSubmit={vi.fn()} />)

    expect(field('Telefonnummer').value).toBe('')
    expect(screen.queryByLabelText(/Testperson übernehmen/)).toBeNull()
  })
})
