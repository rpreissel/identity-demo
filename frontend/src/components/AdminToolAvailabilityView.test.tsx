import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  fetchToolAvailability: vi.fn(),
  setToolAvailability: vi.fn(),
  setToolOrder: vi.fn(),
}))
vi.mock('../api.ts', () => api)

import { AdminToolAvailabilityView } from './AdminToolAvailabilityView'

const version = (toolId: string, v: number, enabled = true) => ({ tool: `${toolId}@${v}`, version: v, enabled, reason: null })
const tool = (toolId: string, enabled = true, role = 'KNOWN_ACCOUNT_AUTH', versions = [version(toolId, 1, enabled)]) => ({
  toolId,
  method: toolId.split('-')[1],
  role,
  versions,
})

/** The channel tab of the given name (App, Website). */
async function openTab(name: string) {
  fireEvent.click(await screen.findByRole('tab', { name: new RegExp(name) }))
}

/** From switching versions to setting the order. */
async function editOrder() {
  fireEvent.click(await screen.findByRole('button', { name: 'Reihenfolge ändern' }))
}

describe('AdminToolAvailabilityView', () => {
  beforeEach(() => {
    vi.clearAllMocks()
    api.fetchToolAvailability.mockResolvedValue([
      { channel: 'APP', tools: [tool('auth-sms'), tool('auth-password')] },
      { channel: 'WEB', tools: [tool('auth-password'), tool('auth-sms', false)] },
    ])
    api.setToolOrder.mockResolvedValue(undefined)
    api.setToolAvailability.mockResolvedValue(undefined)
  })

  it('shows one channel at a time, the App first', async () => {
    render(<AdminToolAvailabilityView />)

    expect(await screen.findByRole('tab', { name: /App/ })).toHaveAttribute('aria-selected', 'true')
    expect(screen.queryByRole('button', { name: 'auth-sms@1 freigeben' })).not.toBeInTheDocument()
  })

  it('moves a tool down in one channel only, saving that channel\'s new order', async () => {
    render(<AdminToolAvailabilityView />)
    await editOrder()

    fireEvent.click(await screen.findByLabelText('auth-sms nach unten'))
    expect(api.setToolOrder).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(api.setToolOrder).toHaveBeenCalledWith('APP', ['auth-password', 'auth-sms'])
  })

  it('drops the draft on "Abbrechen", saving nothing', async () => {
    render(<AdminToolAvailabilityView />)
    await editOrder()

    fireEvent.click(await screen.findByLabelText('auth-sms nach unten'))
    fireEvent.click(screen.getByRole('button', { name: 'Abbrechen' }))

    expect(api.setToolOrder).not.toHaveBeenCalled()
    expect(screen.getByRole('button', { name: 'Reihenfolge ändern' })).toBeInTheDocument()
  })

  it('offers "Speichern" only once the draft differs', async () => {
    render(<AdminToolAvailabilityView />)
    await editOrder()

    expect(await screen.findByRole('button', { name: 'Speichern' })).toBeDisabled()
  })

  it('frees a tool version locked for the Web channel for that channel only', async () => {
    render(<AdminToolAvailabilityView />)
    await openTab('Website')

    fireEvent.click(await screen.findByRole('button', { name: 'auth-sms@1 freigeben' }))

    expect(api.setToolAvailability).toHaveBeenCalledWith('auth-sms@1', 'WEB', true)
  })

  it('locks one version of a tool served in two, leaving the other on', async () => {
    api.fetchToolAvailability.mockResolvedValue([
      { channel: 'APP', tools: [tool('enroll-sms', true, 'ENROLLMENT', [version('enroll-sms', 1), version('enroll-sms', 2)])] },
    ])
    render(<AdminToolAvailabilityView />)

    fireEvent.click(await screen.findByRole('button', { name: 'enroll-sms@1 sperren' }))
    fireEvent.click(screen.getByRole('button', { name: '@1 sperren' }))

    expect(api.setToolAvailability).toHaveBeenCalledWith('enroll-sms@1', 'APP', false, 'manuell gesperrt')
    expect(screen.getByRole('button', { name: 'enroll-sms@2 sperren' })).toBeInTheDocument()
  })

  it('leaves a locked tool out of the order, keeping its place among the others', async () => {
    api.fetchToolAvailability.mockResolvedValue([
      { channel: 'APP', tools: [tool('auth-sms'), tool('auth-kobil', false), tool('auth-password')] },
    ])
    render(<AdminToolAvailabilityView />)
    await editOrder()

    expect(screen.queryByLabelText('auth-kobil nach oben')).not.toBeInTheDocument()
    fireEvent.click(await screen.findByLabelText('auth-sms nach unten'))
    fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))

    expect(api.setToolOrder).toHaveBeenCalledWith('APP', ['auth-password', 'auth-kobil', 'auth-sms'])
  })

  describe('with tools of two roles in one channel', () => {
    beforeEach(() => {
      api.fetchToolAvailability.mockResolvedValue([
        {
          channel: 'APP',
          tools: [tool('auth-sms'), tool('ident-fsc', true, 'IDENTIFICATION'), tool('ident-eid', true, 'IDENTIFICATION')],
        },
      ])
    })

    it('groups the tools by role', async () => {
      render(<AdminToolAvailabilityView />)

      expect(await screen.findByRole('heading', { name: 'Identifizieren' })).toBeInTheDocument()
    })

    it('leaves out a role with a single tool, which has no order to set', async () => {
      render(<AdminToolAvailabilityView />)
      await editOrder()

      expect(await screen.findByLabelText('ident-eid nach oben')).toBeInTheDocument()
      expect(screen.queryByLabelText('auth-sms nach unten')).not.toBeInTheDocument()
    })

    it('moves a tool within its role', async () => {
      render(<AdminToolAvailabilityView />)
      await editOrder()

      fireEvent.click(await screen.findByLabelText('ident-eid nach oben'))
      fireEvent.click(screen.getByRole('button', { name: 'Speichern' }))

      expect(api.setToolOrder).toHaveBeenCalledWith('APP', ['auth-sms', 'ident-eid', 'ident-fsc'])
    })
  })
})
