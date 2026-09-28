import { cleanup, render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BriefkastenApp } from './BriefkastenApp'

/** Each simulated system answers its own path; anything else is an empty list. */
const answers: Record<string, unknown> = {
  '/mock-personenverzeichnis/personen': [
    { id: 'P000000001', vorname: 'Erika', name: 'Muster', mobilnummer: '+49 170 0000001', email: 'erika@example.org' },
  ],
  '/mock-personenverzeichnis/briefe': [
    { id: 1, personId: 'P000000001', freischaltcodeId: 7, code: 'FSC-1111', versandtAm: '2026-09-27T08:00:00Z' },
  ],
  '/mock-sms/outbox': [{ sequence: 1, phoneNumber: '+491700000001', tan: '123456', sentAt: '2026-09-27T08:02:00Z' }],
  '/mock-mail/outbox': [{ sequence: 1, address: 'Erika@example.org', code: '654321', sentAt: '2026-09-27T08:01:00Z' }],
}

beforeEach(() => {
  vi.stubGlobal('fetch', vi.fn(async (input: RequestInfo | URL) => {
    const path = String(input)
    return new Response(JSON.stringify(answers[path] ?? []), { status: 200, headers: { 'Content-Type': 'application/json' } })
  }))
})

afterEach(() => {
  cleanup()
  vi.unstubAllGlobals()
})

describe('Briefkasten', () => {
  it('zeigt Briefe, SMS und E-Mails in einer Liste, neueste zuerst, mit dem Empfänger aus dem Register', async () => {
    render(<BriefkastenApp />)

    await screen.findByText('123456')
    const rows = screen.getAllByRole('row').slice(1)
    expect(rows.map((row) => within(row).getAllByRole('cell')[2].textContent)).toEqual(['123456', '654321', 'FSC-1111'])
    expect(within(rows[0]).getByText('Erika Muster (+491700000001)')).toBeInTheDocument()
    expect(within(rows[1]).getByText('Erika Muster (Erika@example.org)')).toBeInTheDocument()
  })
})
