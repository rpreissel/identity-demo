import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ActiveSessionsReport } from '../../api'
import { WelcomeApp } from './WelcomeApp'

const busy: ActiveSessionsReport = {
  channels: {
    total: 2,
    perType: [
      { channel: 'APP', count: 1 },
      { channel: 'KEYCLOAK', count: 1 },
    ],
    newest: [
      {
        channelSessionId: '11111111-2222-3333-4444-555555555555',
        channel: 'APP',
        state: 'AUTHENTICATED',
        accountId: 7,
        displayName: 'Mara Muster',
        createdAt: '2026-09-26T10:00:00Z',
        lastAccessedAt: '2026-09-26T10:05:00Z',
        expiresAt: '2026-09-26T11:00:00Z',
      },
    ],
  },
  keycloak: { clients: [], error: 'Connection refused' },
}

/** server-info, the session report and the reset - each call recorded. */
function fakeBackend(report: ActiveSessionsReport) {
  return vi.fn(async (path: string, init?: RequestInit) => {
    if (path.endsWith('/demo/sessions')) return new Response(JSON.stringify(report), { status: 200 })
    if (path.endsWith('/demo/reset') && init?.method === 'POST') return new Response(JSON.stringify({ deletedAccounts: 3, endedSessions: 2 }), { status: 200 })
    return new Response(JSON.stringify({ keycloak: null }), { status: 200 })
  })
}

describe('WelcomeApp - Demo zurücksetzen', () => {
  afterEach(() => {
    cleanup()
    vi.unstubAllGlobals()
  })

  it('warns about active sessions, lists them, and resets only after the second click', async () => {
    const fetch = fakeBackend(busy)
    vi.stubGlobal('fetch', fetch)
    render(<WelcomeApp />)

    fireEvent.click(screen.getByRole('button', { name: 'Demo zurücksetzen…' }))
    expect(await screen.findByText(/2 Sitzungen sind gerade aktiv/)).toBeInTheDocument()
    expect(screen.getByText(/Mara Muster/)).toBeInTheDocument()
    expect(screen.getByText(/Keycloak ist nicht erreichbar: Connection refused/)).toBeInTheDocument()
    expect(fetch.mock.calls.some(([path]) => String(path).endsWith('/demo/reset'))).toBe(false)

    fireEvent.click(screen.getByRole('button', { name: 'Jetzt zurücksetzen' }))
    expect(await screen.findByText('Demo zurückgesetzt. Gelöschte Konten: 3. Beendete Sitzungen: 2.')).toBeInTheDocument()
    expect(screen.queryByText(/Sitzungen sind gerade aktiv/)).not.toBeInTheDocument()
  })

  it('says so when nobody is using the demo, and cancelling resets nothing', async () => {
    const fetch = fakeBackend({ channels: { total: 0, perType: [], newest: [] }, keycloak: null })
    vi.stubGlobal('fetch', fetch)
    render(<WelcomeApp />)

    fireEvent.click(screen.getByRole('button', { name: 'Demo zurücksetzen…' }))
    expect(await screen.findByText('Gerade ist keine Sitzung aktiv.')).toBeInTheDocument()
    fireEvent.click(screen.getByRole('button', { name: 'Abbrechen' }))

    expect(screen.getByRole('button', { name: 'Demo zurücksetzen…' })).toBeInTheDocument()
    expect(fetch.mock.calls.some(([path]) => String(path).endsWith('/demo/reset'))).toBe(false)
  })
})
