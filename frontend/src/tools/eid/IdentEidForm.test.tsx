import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { IdentEidForm } from './IdentEidForm'

const CARD_FIELDS = ['familyName', 'givenNames', 'birthDate', 'streetAddress', 'postalCode', 'locality', 'restrictedId']

const max = {
  personId: 'P000000001',
  kvnr: 'A123456789',
  familyName: 'Muster',
  givenNames: 'Max',
  birthDate: '1985-06-15',
  streetAddress: 'Musterstraße 1',
  postalCode: '12345',
  locality: 'Musterstadt',
  restrictedId: 'T0103005K1D5S0V8T9W6UM2RTX',
}
const erika = {
  personId: 'P000000002',
  kvnr: 'B987654321',
  familyName: 'Beispiel',
  givenNames: 'Erika',
  birthDate: '1990-11-02',
  streetAddress: 'Beispielweg 42',
  postalCode: '54321',
  locality: 'Beispielhausen',
  restrictedId: 'T0208011X7Y2Q4M6B3LT0T28WJ',
}

const maxCard = {
  familyName: 'Muster',
  givenNames: 'Max',
  birthDate: '1985-06-15',
  streetAddress: 'Musterstraße 1',
  postalCode: '12345',
  locality: 'Musterstadt',
  restrictedId: 'T0103005K1D5S0V8T9W6UM2RTX',
}

describe('IdentEidForm', () => {
  afterEach(cleanup)

  it('reads the card first and PATCHes only the card data', () => {
    const onSubmit = vi.fn()
    render(<IdentEidForm onSubmit={onSubmit} missingFields={CARD_FIELDS} demoPersons={[max]} />)

    expect(screen.queryByLabelText('PIN')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Karte auflegen (simuliert)' }))

    expect(onSubmit).toHaveBeenCalledWith(maxCard)
  })

  it('shows only the PIN once the backend asks for nothing but pin', () => {
    const onSubmit = vi.fn()
    render(<IdentEidForm onSubmit={onSubmit} missingFields={['pin']} demoPersons={[max]} />)

    expect(screen.queryByLabelText('Nachname')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'Identifizieren' }))

    expect(onSubmit).toHaveBeenCalledWith({ pin: '123456' })
  })

  it('stays on the card page when the card data was rejected', () => {
    const { rerender } = render(<IdentEidForm onSubmit={vi.fn()} missingFields={CARD_FIELDS} demoPersons={[max]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Karte auflegen (simuliert)' }))

    rerender(<IdentEidForm onSubmit={vi.fn()} error="Die Kartendaten sind ungültig" demoPersons={[max]} />)

    expect(screen.getByLabelText('Nachname')).toBeTruthy()
    expect(screen.getByText('Die Kartendaten sind ungültig')).toBeTruthy()
  })

  it('stays on the PIN page when the PIN was rejected', () => {
    const { rerender } = render(<IdentEidForm onSubmit={vi.fn()} missingFields={['pin']} demoPersons={[max]} />)
    fireEvent.click(screen.getByRole('button', { name: 'Identifizieren' }))

    rerender(<IdentEidForm onSubmit={vi.fn()} error="eID-PIN ungueltig" demoPersons={[max]} />)

    expect(screen.getByLabelText('PIN')).toBeTruthy()
  })

  it('goes back to the card with "Angaben ändern" and sends the corrected card on its own', () => {
    const onSubmit = vi.fn()
    render(<IdentEidForm onSubmit={onSubmit} missingFields={['pin']} demoPersons={[max]} />)

    fireEvent.click(screen.getByRole('button', { name: 'Angaben ändern' }))
    fireEvent.change(screen.getByLabelText('PLZ'), { target: { value: '12346' } })
    fireEvent.click(screen.getByRole('button', { name: 'Karte auflegen (simuliert)' }))

    expect(onSubmit).toHaveBeenCalledWith({ ...maxCard, postalCode: '12346' })
  })

  it('keeps the demo-person picker on the card the form holds when going back', () => {
    const { rerender } = render(<IdentEidForm onSubmit={vi.fn()} missingFields={CARD_FIELDS} demoPersons={[max, erika]} />)
    fireEvent.change(screen.getByLabelText(/Testperson übernehmen/), { target: { value: 'P000000002' } })
    fireEvent.click(screen.getByRole('button', { name: 'Karte auflegen (simuliert)' }))
    rerender(<IdentEidForm onSubmit={vi.fn()} missingFields={['pin']} demoPersons={[max, erika]} />)

    fireEvent.click(screen.getByRole('button', { name: 'Angaben ändern' }))

    expect((screen.getByLabelText(/Testperson übernehmen/) as HTMLSelectElement).value).toBe('P000000002')
    expect((screen.getByLabelText('Vorname') as HTMLInputElement).value).toBe('Erika')
  })

  it('starts empty without demo values (ADR-28)', () => {
    render(<IdentEidForm onSubmit={vi.fn()} missingFields={CARD_FIELDS} />)
    expect((screen.getByLabelText('Nachname') as HTMLInputElement).value).toBe('')
  })
})
