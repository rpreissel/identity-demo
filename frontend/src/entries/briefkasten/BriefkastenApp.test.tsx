import { render, screen, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { BriefkastenApp } from './BriefkastenApp'

/** Each simulated system answers its own path; anything else is an empty list. */
const answers: Record<string, unknown> = {
  '/mock-personenverzeichnis/personen': [
    { id: 'P000000001', vorname: 'Erika', name: 'Muster', mobilnummer: '+49 170 0000001', email: 'erika@example.org' },
  ],
  '/mock-personenverzeichnis/briefe': [
    { id: 1, personId: 'P000000001', art: 'FREISCHALTCODE', freischaltcodeId: 7, code: 'FSC-1111', versandtAm: '2026-09-27T08:00:00Z' },
    { id: 2, personId: 'P000000001', art: 'EINMALKENNWORT', einladungId: 'a'.repeat(64), vorgang: 'bonusprogramm', code: 'ABCD-EFGH-JKLM', versandtAm: '2026-09-26T08:00:00Z' },
  ],
  '/mock-personenverzeichnis/vorgaenge': [{ id: 'bonusprogramm', name: 'Bonusprogramm' }],
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
  vi.unstubAllGlobals()
})

describe('Briefkasten', () => {
  it('zeigt Briefe, SMS und E-Mails in einer Liste, neueste zuerst, mit dem Empfänger aus dem Register', async () => {
    render(<BriefkastenApp />)

    await screen.findByText('123456')
    const items = screen.getAllByRole('listitem')
    expect(items.map((item) => item.querySelector('.mailbox-code')?.textContent)).toEqual(['123456', '654321', 'FSC-1111', 'ABCD-EFGH-JKLM'])
    expect(within(items[0]).getByText('SMS an Erika Muster')).toBeInTheDocument()
    expect(within(items[0]).getByText('+491700000001')).toBeInTheDocument()
    expect(within(items[1]).getByText('E-Mail an Erika Muster')).toBeInTheDocument()
    expect(within(items[1]).getByText('Erika@example.org')).toBeInTheDocument()
  })

  it('nennt beim Brief mit Einmalkennwort den Vorgang mit seinem Namen (ADR-48)', async () => {
    render(<BriefkastenApp />)

    await screen.findByText('ABCD-EFGH-JKLM')
    const row = screen.getByText('ABCD-EFGH-JKLM').closest('li')!
    expect(within(row).getByText('Einmalkennwort für Bonusprogramm')).toBeInTheDocument()
  })
})
