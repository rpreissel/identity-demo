import { fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { AdminApp } from './AdminApp'
import { clearAdminCredentials } from '../../adminAuth'

/** Just enough of fetch for the admin page: 401 unless the Basic header carries admin/admin. */
function fakeBackend() {
  return vi.fn(async (path: string, init?: RequestInit) => {
    const auth = (init?.headers as Record<string, string> | undefined)?.Authorization
    if (auth !== `Basic ${btoa('admin:admin')}`) return new Response('', { status: 401 })
    const body = path.endsWith('/tools/availability')
      ? []
      : path.endsWith('/sessions')
        ? { channels: { total: 1, perType: [{ channel: 'WEB', count: 1 }], newest: [] }, keycloak: null }
        : { enrollFirst: false }
    return new Response(JSON.stringify(body), { status: 200 })
  })
}

describe('AdminApp', () => {
  afterEach(() => {
    clearAdminCredentials()
    vi.unstubAllGlobals()
  })

  it('keeps the login form on wrong credentials', async () => {
    vi.stubGlobal('fetch', fakeBackend())
    render(<AdminApp />)

    logIn('falsch')

    expect(await screen.findByText(/Anmeldung fehlgeschlagen/)).toBeInTheDocument()
    expect(screen.getByLabelText('Passwort')).toBeInTheDocument()
  })

  it('opens the admin tabs on the right credentials', async () => {
    vi.stubGlobal('fetch', fakeBackend())
    render(<AdminApp />)

    logIn('admin')

    expect(await screen.findByRole('tab', { name: 'Journey-Trace' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Abmelden' })).toBeInTheDocument()
  })

  it('shows the active sessions in the sessions tab', async () => {
    vi.stubGlobal('fetch', fakeBackend())
    render(<AdminApp />)
    logIn('admin')
    await screen.findByRole('tab', { name: 'Sitzungen' })

    fireEvent.click(screen.getByRole('tab', { name: 'Sitzungen' }))

    expect(await screen.findByText('1 aktiv')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Aktualisieren' })).toBeInTheDocument()
  })
})

function logIn(password: string) {
  fireEvent.change(screen.getByLabelText('Passwort'), { target: { value: password } })
  fireEvent.click(screen.getByRole('button', { name: 'Anmelden' }))
}
