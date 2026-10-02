import { fireEvent, render, screen } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'
import { IdentKvnrForm } from './IdentKvnrForm'

const max = { personId: 'P000000001', kvnr: 'A123456789', givenNames: 'Max', familyName: 'Muster' }
const paula = { personId: 'P000000004', kvnr: null, givenNames: 'Paula', familyName: 'Schulz' }

describe('IdentKvnrForm', () => {
  it('prefills the first test person’s KVNR', () => {
    const onSubmit = vi.fn()
    render(<IdentKvnrForm onSubmit={onSubmit} skipLabel="Überspringen" demoPersons={[max, paula]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Zuordnen' }))

    expect(onSubmit).toHaveBeenCalledWith({ kvnr: 'A123456789' })
  })

  it('prefills the Partnernummer when the first test person is a Partner', () => {
    const onSubmit = vi.fn()
    render(<IdentKvnrForm onSubmit={onSubmit} skipLabel="Überspringen" demoPersons={[paula, max]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Zuordnen' }))

    expect(onSubmit).toHaveBeenCalledWith({ partnerNumber: 'P000000004' })
  })

  it('starts empty without demo values (ADR-28)', () => {
    render(<IdentKvnrForm onSubmit={vi.fn()} skipLabel="Überspringen" />)
    expect((screen.getByLabelText('Versichertennummer') as HTMLInputElement).value).toBe('')
  })
})
