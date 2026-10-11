import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import type { JourneyTraceChannel, JourneyTraceEntryView, JourneyTracePage, JourneyTraceSummary } from '../types'
import { toolLabel } from '../journeyTraceText'
import { JourneyTraceView } from './JourneyTraceView'

const PARENT = '11111111-1111-4111-8111-111111111111'
const SUB = '22222222-2222-4222-8222-222222222222'
const CHANNEL = '33333333-3333-4333-8333-333333333333'

const summary: JourneyTraceSummary = {
  journeyId: PARENT,
  channelSessionId: CHANNEL,
  channelType: 'APP',
  accountId: 4,
  displayName: 'Tina Tester',
  intent: 'MANAGE_AUTH_METHODS',
  startedAt: '2026-10-10T09:41:02Z',
  lastActivityAt: '2026-10-10T09:42:16Z',
  stepCount: 4,
  failedAttempts: 0,
  outcome: 'SUCCEEDED',
}

function entry(createdAt: string, eventType: string, journeyId: string, extra: Partial<JourneyTraceEntryView> = {}): JourneyTraceEntryView {
  return { channelSessionId: CHANNEL, channelType: 'APP', accountId: 4, journeyId, intent: 'MANAGE_AUTH_METHODS', eventType, detail: {}, createdAt, ...extra }
}

const channel: JourneyTraceChannel = {
  channelSessionId: CHANNEL,
  channelType: 'APP',
  accountId: 4,
  displayName: 'Tina Tester',
  truncated: false,
  journeys: [summary, { ...summary, journeyId: SUB, parentJourneyId: PARENT, intent: 'STEP_UP', stepCount: 2, failedAttempts: 1 }],
  entries: [
    entry('2026-10-10T09:41:02Z', 'Started', PARENT),
    entry('2026-10-10T09:41:09Z', 'Answered', PARENT, { detail: { answer: 'confirm', decision: 'RequireSubJourney', subIntent: 'STEP_UP', targetAcr: 'loa3' } }),
    entry('2026-10-10T09:41:10Z', 'Started', SUB, { parentJourneyId: PARENT, intent: 'STEP_UP' }),
    entry('2026-10-10T09:41:38Z', 'TOOL_FAILED', SUB, { parentJourneyId: PARENT, intent: 'STEP_UP', detail: { toolId: 'auth-sms', attemptBudgetLeft: 4 } }),
    entry('2026-10-10T09:42:15Z', 'SubJourneyFinished', PARENT, { detail: { subIntent: 'STEP_UP', decision: 'Authenticated' } }),
  ],
}

const page = (journeys: JourneyTraceSummary[], nextCursor?: string): JourneyTracePage => ({ journeys, nextCursor, searchedUntil: journeys.at(-1)?.startedAt })

describe('JourneyTraceView', () => {
  it('lists the newest journeys of the last day with person and outcome', async () => {
    const search = vi.fn().mockResolvedValue(page([summary, { ...summary, journeyId: SUB }]))

    render(<JourneyTraceView search={search} fetchChannel={vi.fn()} lookupAccount={vi.fn()} />)

    expect(await screen.findAllByText('Tina Tester')).toHaveLength(2)
    expect(screen.getAllByText('Erfolgreich').length).toBeGreaterThan(0)
    const first = search.mock.calls[0][0]
    expect(first.limit).toBe(50)
    expect(first.accountId).toBeUndefined()
    expect(Date.now() - Date.parse(first.from)).toBeGreaterThan(23 * 3600_000)
  })

  it('opens a journey with its sub-journey inside the step that required it', async () => {
    const search = vi.fn().mockResolvedValue(page([summary, { ...summary, journeyId: SUB }]))
    const fetchChannel = vi.fn().mockResolvedValue(channel)

    render(<JourneyTraceView search={search} fetchChannel={fetchChannel} lookupAccount={vi.fn()} />)
    await userEvent.click((await screen.findAllByRole('button', { name: 'Anmeldeverfahren verwalten' }))[0])

    const parent = (await screen.findByRole('heading', { name: 'Anmeldeverfahren verwalten', level: 4 })).closest('section')!
    const steps = within(parent).getAllByRole('listitem').filter((li) => li.parentElement?.parentElement === parent)
    // Started, Answered (requires the sub-journey), the sub-journey itself, then back in the parent.
    expect(steps).toHaveLength(4)
    expect(within(steps[2]).getByText('Sub-Journey, Voraussetzung')).toBeInTheDocument()
    expect(within(steps[2]).getByText(`${toolLabel('auth-sms')} fehlgeschlagen`)).toBeInTheDocument()
    expect(within(steps[3]).getByText(/zurück in dieser Journey/)).toBeInTheDocument()
    expect(fetchChannel).toHaveBeenCalledWith(CHANNEL)
  })

  it('filters a channel’s steps by kind', async () => {
    const search = vi.fn().mockResolvedValue(page([summary]))

    render(<JourneyTraceView search={search} fetchChannel={vi.fn().mockResolvedValue(channel)} lookupAccount={vi.fn()} />)
    expect(await screen.findByText(`${toolLabel('auth-sms')} fehlgeschlagen`)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: /Fehlversuche/ }))

    expect(screen.queryByText(`${toolLabel('auth-sms')} fehlgeschlagen`)).not.toBeInTheDocument()
    expect(screen.getByText('4 von 5 Schritten')).toBeInTheDocument()
  })

  it('finds the person behind an identifier, then searches that account’s journeys', async () => {
    const search = vi.fn().mockResolvedValue(page([]))
    const lookupAccount = vi.fn().mockResolvedValue({ matches: [{ accountId: 12, displayName: 'Max Muster', matchedBy: 'EMAIL' }] })

    render(<JourneyTraceView search={search} fetchChannel={vi.fn()} lookupAccount={lookupAccount} />)
    await userEvent.type(screen.getByLabelText('Person oder ID'), 'max@example.com{Enter}')

    expect(lookupAccount).toHaveBeenCalledWith('max@example.com')
    expect(search).toHaveBeenLastCalledWith(expect.objectContaining({ accountId: 12 }))
    expect(await screen.findByText('Journeys von Max Muster (Konto 12), gefunden über E-Mail-Adresse')).toBeInTheDocument()
  })

  it('lets the reader pick when eight digits match a Versicherungsnummer and an account number', async () => {
    const search = vi.fn().mockResolvedValue(page([]))
    const lookupAccount = vi.fn().mockResolvedValue({
      matches: [
        { accountId: 7, displayName: 'Max Muster', matchedBy: 'MEMBER_NUMBER' },
        { accountId: 12345678, displayName: 'Erika Beispiel', matchedBy: 'ACCOUNT_NUMBER' },
      ],
    })

    render(<JourneyTraceView search={search} fetchChannel={vi.fn()} lookupAccount={lookupAccount} />)
    await userEvent.type(screen.getByLabelText('Person oder ID'), '12345678{Enter}')
    await userEvent.click(await screen.findByRole('button', { name: 'Max Muster, gefunden über Versicherungsnummer' }))

    expect(search).toHaveBeenLastCalledWith(expect.objectContaining({ accountId: 7 }))
  })

  it('says so when no account holds the value', async () => {
    render(<JourneyTraceView search={vi.fn().mockResolvedValue(page([]))} fetchChannel={vi.fn()} lookupAccount={vi.fn().mockResolvedValue({ matches: [] })} />)

    await userEvent.type(screen.getByLabelText('Person oder ID'), 'P000000000{Enter}')

    expect(await screen.findByText('Zu dieser Angabe gibt es kein Konto.')).toBeInTheDocument()
  })

  it('tries an unknown UUID as a channel, without asking the account lookup', async () => {
    const search = vi.fn().mockResolvedValue(page([]))
    const lookupAccount = vi.fn()

    render(<JourneyTraceView search={search} fetchChannel={vi.fn()} lookupAccount={lookupAccount} />)
    await userEvent.type(screen.getByLabelText('Person oder ID'), `${CHANNEL}{Enter}`)

    expect(search).toHaveBeenCalledWith(expect.objectContaining({ journeyId: CHANNEL }))
    expect(search).toHaveBeenLastCalledWith(expect.objectContaining({ channelSessionId: CHANNEL, journeyId: undefined }))
    expect(lookupAccount).not.toHaveBeenCalled()
  })

  it('continues an incomplete search from where it stopped', async () => {
    const search = vi
      .fn()
      .mockResolvedValueOnce(page([summary], 'cursor-1'))
      .mockResolvedValueOnce(page([{ ...summary, journeyId: SUB, displayName: 'Max Muster' }]))

    render(<JourneyTraceView search={search} fetchChannel={vi.fn().mockResolvedValue(channel)} lookupAccount={vi.fn()} />)
    await userEvent.click(await screen.findByRole('button', { name: 'Ältere laden' }))

    expect(search).toHaveBeenLastCalledWith(expect.objectContaining({ cursor: 'cursor-1' }))
    expect(await screen.findByText('Max Muster')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Ältere laden' })).not.toBeInTheDocument()
  })
})
