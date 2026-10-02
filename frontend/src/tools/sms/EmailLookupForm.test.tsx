import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { EmailLookupForm } from './EmailLookupForm'

const max = { personId: 'P000000001', givenNames: 'Max', familyName: 'Muster', email: 'max.mustermann@example.com', phoneNumber: '+49 170 0000001' }
const erika = { personId: 'P000000002', givenNames: 'Erika', familyName: 'Beispiel', email: 'erika.beispiel@example.com', phoneNumber: '+49 170 0000002' }

function field(label: string): HTMLInputElement {
  return screen.getByLabelText(label) as HTMLInputElement
}

describe('EmailLookupForm', () => {
  it('prefills the first test person’s e-mail address', () => {
    render(<EmailLookupForm onSubmit={vi.fn()} demoPersons={[max, erika]} />)

    expect(field('E-Mail-Adresse').value).toBe('max.mustermann@example.com')
  })

  it('takes the e-mail address of the test person picked', () => {
    render(<EmailLookupForm onSubmit={vi.fn()} demoPersons={[max, erika]} />)

    fireEvent.change(screen.getByLabelText(/Testperson übernehmen/), { target: { value: 'P000000002' } })

    expect(field('E-Mail-Adresse').value).toBe('erika.beispiel@example.com')
  })

  it('starts empty without demo values (ADR-28)', () => {
    render(<EmailLookupForm onSubmit={vi.fn()} />)

    expect(field('E-Mail-Adresse').value).toBe('')
  })
})
