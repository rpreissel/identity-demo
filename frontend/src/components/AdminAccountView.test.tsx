import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { AccountDetails, AccountMatch } from '../types'
import { AdminAccountView } from './AdminAccountView'

const details: AccountDetails = {
  accountId: 12,
  displayName: 'Max Muster',
  role: 'INSURED',
  createdAt: '2026-10-03T09:12:00Z',
  anchors: [
    { attributeType: 'person_id', value: 'P100000001', establishedAcr: 'loa2', establishedAt: '2026-10-03T09:12:00Z' },
    { attributeType: 'email', value: 'max.muster@example.com', establishedAcr: 'loa1', establishedAt: '2026-10-05T18:40:00Z' },
  ],
  claims: [
    { id: 1, attributeType: 'family_name', maskedValue: 'Mu•••', readable: true, claimSource: 'person_directory', establishedAcr: 'loa2', establishedAt: '2026-10-03T09:12:00Z' },
    {
      id: 2,
      attributeType: 'email',
      maskedValue: 'ma•••',
      readable: true,
      claimSource: 'self-reported',
      establishedAcr: 'loa1',
      establishedAt: '2026-10-03T09:15:00Z',
      retraction: { id: 7, attributeType: 'email', source: 'ACCOUNT_HOLDER', reason: 'durch neue Adresse ersetzt', retractedAt: '2026-10-05T18:40:00Z' },
    },
    { id: 3, attributeType: 'email', maskedValue: 'ma•••', readable: true, claimSource: 'self-reported', establishedAcr: 'loa1', establishedAt: '2026-10-05T18:40:00Z' },
    { id: 4, attributeType: 'street_address', readable: false, claimSource: 'ident-eid', establishedAcr: 'loa3', establishedAt: '2026-10-04T11:02:00Z' },
  ],
  retractions: [{ id: 7, attributeType: 'email', source: 'ACCOUNT_HOLDER', reason: 'durch neue Adresse ersetzt', retractedAt: '2026-10-05T18:40:00Z' }],
  methods: [
    { id: 'm1', method: 'sms', label: null, active: true, enrolledUnderAcr: 'loa2', createdAt: '2026-10-03T09:13:00Z' },
    { id: 'm2', method: 'kobil', label: 'KOBIL App', active: false, enrolledUnderAcr: 'loa3', boundKeyRef: 'abcdef123456', createdAt: '2026-10-04T11:02:00Z', deactivatedAt: '2026-10-06T08:55:00Z' },
  ],
  devices: [{ bindingKeyRef: 'key-7f3a99', linkedAt: '2026-10-03T09:14:00Z', lastLinkedAt: '2026-10-10T09:41:00Z' }],
  changeLog: [{ changeType: 'IDENTIFIED', subject: 'ident-fsc', acr: 'loa2', details: {}, occurredAt: '2026-10-03T09:12:00Z' }],
  signIns: [{ signInType: 'SIGNED_IN', channel: 'APP', acr: 'loa2', details: { amr: ['hwk'] }, occurredAt: '2026-10-10T09:40:00Z' }],
}

function setup(matches: AccountMatch[] = [{ accountId: 12, displayName: 'Max Muster', matchedBy: 'KVNR' }]) {
  const props = {
    lookupAccount: vi.fn().mockResolvedValue({ matches }),
    fetchDetails: vi.fn().mockResolvedValue(details),
    fetchClaimValues: vi.fn().mockResolvedValue({ values: { '1': 'Muster', '2': 'max@alt.example', '3': 'max.muster@example.com' } }),
    onOpenTrace: vi.fn(),
  }
  render(<AdminAccountView {...props} />)
  return props
}

async function search(value: string) {
  await userEvent.type(screen.getByLabelText('Person oder Konto'), `${value}{Enter}`)
}

describe('AdminAccountView', () => {
  it('finds an account by an identifier and shows who it is, without any list of accounts', async () => {
    const props = setup()

    await search('a123456789')

    expect(props.lookupAccount).toHaveBeenCalledWith('a123456789')
    expect(await screen.findByRole('heading', { name: 'Max Muster' })).toBeInTheDocument()
    expect(screen.getAllByText('Versicherter').length).toBeGreaterThan(0)
    expect(screen.getByText(/gefunden über KVNR/)).toBeInTheDocument()
    expect(props.fetchDetails).toHaveBeenCalledWith(12)
  })

  it('lets the operator pick when two accounts match', async () => {
    const props = setup([
      { accountId: 12, displayName: 'Max Muster', matchedBy: 'MEMBER_NUMBER' },
      { accountId: 12345678, displayName: 'Erika Beispiel', matchedBy: 'ACCOUNT_NUMBER' },
    ])

    await search('12345678')
    await userEvent.click(await screen.findByRole('button', { name: 'Max Muster, gefunden über Versicherungsnummer' }))

    expect(props.fetchDetails).toHaveBeenCalledWith(12)
  })

  it('shows claims masked, retracted ones struck through, erased ones as unreadable', async () => {
    setup()
    await search('a123456789')
    await userEvent.click(await screen.findByRole('tab', { name: /Angaben/ }))

    expect(screen.getByText('Mu•••')).toBeInTheDocument()
    expect(screen.getByText('nicht mehr lesbar, Frist abgelaufen')).toBeInTheDocument()
    expect(screen.getAllByText('zurückgezogen')).toHaveLength(1)

    await userEvent.click(screen.getByRole('button', { name: 'Zurückgezogene ausblenden' }))
    expect(screen.queryByText('zurückgezogen')).not.toBeInTheDocument()
  })

  it('shows full values only on request', async () => {
    const props = setup()
    await search('a123456789')
    await userEvent.click(await screen.findByRole('tab', { name: /Angaben/ }))
    expect(props.fetchClaimValues).not.toHaveBeenCalled()

    await userEvent.click(screen.getByRole('button', { name: 'Werte anzeigen' }))

    expect(await screen.findByText('Muster')).toBeInTheDocument()
    expect(props.fetchClaimValues).toHaveBeenCalledWith(12)
  })

  it('opens the history of an attribute: confirmed, withdrawn and why, anchored', async () => {
    setup()
    await search('a123456789')
    await userEvent.click(await screen.findByRole('tab', { name: /Angaben/ }))

    await userEvent.click(screen.getAllByRole('button', { name: 'E-Mail-Adresse' })[0])

    const history = screen.getByRole('list', { name: 'Geschichte von E-Mail-Adresse' })
    const steps = within(history).getAllByRole('listitem')
    expect(steps.map((step) => step.querySelector('strong')?.textContent)).toEqual([
      'Bestätigt',
      'Zurückgezogen',
      'Bestätigt',
      'Als Anker festgelegt',
    ])
    expect(within(steps[1]).getByText(/Grund: durch neue Adresse ersetzt/)).toBeInTheDocument()
  })

  it('lists removed methods with their date, and the linked devices', async () => {
    setup()
    await search('a123456789')
    await userEvent.click(await screen.findByRole('tab', { name: /Verfahren und Geräte/ }))

    expect(screen.getByText(/entfernt am/)).toBeInTheDocument()
    expect(screen.getByText('…7f3a99')).toBeInTheDocument()
  })

  it('merges both logs and filters them', async () => {
    setup()
    await search('a123456789')
    await userEvent.click(await screen.findByRole('tab', { name: /Protokolle/ }))

    expect(screen.getByText('Identifiziert')).toBeInTheDocument()
    expect(screen.getByText('Angemeldet')).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Änderungsprotokoll' }))
    expect(screen.queryByText('Angemeldet')).not.toBeInTheDocument()
  })

  it('opens the journey trace for the account', async () => {
    const props = setup()
    await search('a123456789')

    await userEvent.click(await screen.findByRole('button', { name: 'Journey-Trace öffnen' }))

    expect(props.onOpenTrace).toHaveBeenCalledWith(12)
  })
})
