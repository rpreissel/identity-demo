import { fireEvent, render, screen } from '@testing-library/react'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { DpopKeyPair } from '../dpop.ts'

const api = vi.hoisted(() => ({
  getIdClaims: vi.fn(),
  getToken: vi.fn(),
}))
vi.mock('../api.ts', async (importActual) => ({ ...(await importActual<typeof import('../api.ts')>()), ...api }))

import { AuthenticationCompletedView } from './AuthenticationCompletedView'

const token = {
  accessToken: 'header.e30.signature',
  tokenType: 'DPoP',
  accessExpiresAt: new Date(Date.now() + 300_000).toISOString(),
  refreshExpiresAt: new Date(Date.now() + 1_800_000).toISOString(),
}

function renderHome() {
  const noop = () => {}
  render(
    <AuthenticationCompletedView
      dpop={{} as DpopKeyPair}
      channelSessionId="channel-1"
      currentAcr="loa2"
      view="home"
      onNavigate={noop}
      onAddMethod={noop}
      onDeactivateMethod={noop}
      onChangeMethod={noop}
      onStepUp={noop}
      onDeleteAccount={noop}
      onPeerLogin={noop}
      onLogout={noop}
    />,
  )
}

describe('AuthenticationCompletedView', () => {
  beforeEach(() => {
    api.getIdClaims.mockReset()
    api.getToken.mockReset().mockResolvedValue(token)
  })

  it('fetches the ID claims again after the token was renewed, so a changed name shows', async () => {
    api.getIdClaims.mockResolvedValueOnce({ name: 'Max Muster' }).mockResolvedValueOnce({ name: 'Maximilian Muster' })
    renderHome()
    expect(await screen.findByRole('heading', { name: 'Willkommen, Max Muster!' })).toBeInTheDocument()

    fireEvent.click(await screen.findByRole('button', { name: 'AccessToken aktualisieren' }))

    expect(await screen.findByRole('heading', { name: 'Willkommen, Maximilian Muster!' })).toBeInTheDocument()
    expect(api.getIdClaims).toHaveBeenCalledTimes(2)
  })
})
