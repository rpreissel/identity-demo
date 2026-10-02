import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  fetchToolAvailability: vi.fn(),
  setToolAvailability: vi.fn(),
  setToolOrder: vi.fn(),
}))
vi.mock('../api.ts', () => api)

import { AdminToolAvailabilityView } from './AdminToolAvailabilityView'

const tool = (toolId: string, enabled = true) => ({ toolId, method: toolId.split('-')[1], role: 'KNOWN_ACCOUNT_AUTH', enabled })

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

  it('moves a tool down in one channel only, saving that channel\'s new order', async () => {
    render(<AdminToolAvailabilityView />)
    const downButtons = await screen.findAllByLabelText('auth-sms nach unten')

    fireEvent.click(downButtons[0])

    expect(api.setToolOrder).toHaveBeenCalledWith('APP', ['auth-password', 'auth-sms'])
  })

  it('frees a tool locked for the Web channel for that channel only', async () => {
    render(<AdminToolAvailabilityView />)

    fireEvent.click(await screen.findByRole('button', { name: 'Freigeben' }))

    expect(api.setToolAvailability).toHaveBeenCalledWith('auth-sms', 'WEB', true)
  })

  describe('with tools of two roles in one channel', () => {
    beforeEach(() => {
      api.fetchToolAvailability.mockResolvedValue([
        {
          channel: 'APP',
          tools: [tool('auth-sms'), { toolId: 'ident-fsc', method: 'fsc', role: 'IDENTIFICATION', enabled: true }, { toolId: 'ident-eid', method: 'eid', role: 'IDENTIFICATION', enabled: true }],
        },
      ])
    })

    it('groups the tools by role', async () => {
      render(<AdminToolAvailabilityView />)

      expect(await screen.findByRole('heading', { name: 'Identifizieren' })).toBeInTheDocument()
    })

    it('gives the only tool of a role nowhere to move, even though tools of another role follow', async () => {
      render(<AdminToolAvailabilityView />)

      expect(await screen.findByLabelText('auth-sms nach unten')).toBeDisabled()
    })

    it('moves a tool within its role', async () => {
      render(<AdminToolAvailabilityView />)

      fireEvent.click(await screen.findByLabelText('ident-eid nach oben'))

      expect(api.setToolOrder).toHaveBeenCalledWith('APP', ['auth-sms', 'ident-eid', 'ident-fsc'])
    })
  })
})
