import { cleanup, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { AppChannelApp } from './AppChannelApp'
import type { ChannelResponse, Prompt } from '../../types'

vi.mock('../../dpop.ts', () => ({
  getOrCreateDpopKeyPair: vi.fn().mockResolvedValue({ keyPair: {} as CryptoKeyPair, publicJwk: {} as JsonWebKey }),
  computeJwkThumbprint: vi.fn().mockResolvedValue('fake-thumbprint'),
  resetDpopKeyPair: vi.fn().mockResolvedValue(undefined),
}))

const api = vi.hoisted(() => ({
  createChannel: vi.fn(),
  getChannel: vi.fn(),
  raiseRequiredAcr: vi.fn(),
  cancelJourney: vi.fn(),
  logoutChannel: vi.fn(),
  getMethods: vi.fn(),
  startManageMethods: vi.fn(),
  deactivateMethod: vi.fn(),
  activateTool: vi.fn(),
  patchTool: vi.fn(),
  getTool: vi.fn(),
  startPeerLogin: vi.fn(),
  getDeviceLink: vi.fn(),
}))

vi.mock('../../api.ts', async (importOriginal) => {
  const actual = await importOriginal<typeof import('../../api.ts')>()
  return { ...actual, ...api, onApiCall: () => () => {} }
})

/** Fills in only what applyResponse actually reads; individual tests override per case. */
function channelResponse(overrides: Partial<ChannelResponse> & { channel: ChannelResponse['channel'] }): ChannelResponse {
  return { next: undefined, stepData: undefined, demo: undefined, ...overrides }
}

beforeEach(() => {
  window.localStorage.clear()
  // Reset the hash/search so a later test doesn't inherit the sub-tab or query the previous one left.
  window.location.hash = ''
  window.history.replaceState(null, '', '/')
  vi.clearAllMocks()
  // A device no account knows yet, unless a test says otherwise.
  api.getDeviceLink.mockResolvedValue({ linked: false })
})

afterEach(() => {
  cleanup()
})

describe('resume mid-tool (docs/05-api.md #2: next.toolSessionId)', () => {
  it('reuses the running ToolSession instead of reactivating the tool', async () => {
    window.localStorage.setItem('identity-demo-channel-session-id', 'chan-1')
    const resumedNext = { type: 'tool', toolId: 'enroll-sms', step: 'tanInput', toolSessionId: 'ts-resumed' } as const
    api.getChannel.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'REGISTERING' },
        next: resumedNext,
      })
    )
    // The channel-level GET reports only a bare pointer; the client fetches the tool's stepData
    // separately (ToolControllerSupport.buildReadResponse).
    api.getTool.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'REGISTERING' },
        next: resumedNext,
        demo: { tan: '123456' },
      })
    )

    render(<AppChannelApp />)
    const user = userEvent.setup()

    const resumeButton = await screen.findByRole('button', { name: /Sitzung fortsetzen/ })
    await user.click(resumeButton)

    // The resumed step (tanInput) renders directly - no second activation round-trip, no second TAN.
    await screen.findByRole('heading', { name: 'TAN eingeben' })
    expect(api.activateTool).not.toHaveBeenCalled()
    // ...but its stepData (here: the demo TAN hint) is re-fetched via the per-tool GET.
    expect(api.getTool).toHaveBeenCalledWith(expect.anything(), 'ts-resumed', 'enroll-sms')
  })
})

describe('security-summary backfill (docs/05-api.md #2: on-demand, not part of tool responses)', () => {
  it('fetches currentAcr/currentAmr/activeMethods once, only after settling into authenticated', async () => {
    const enrollNext = { type: 'tool', toolId: 'enroll-sms', step: 'enroll', toolSessionId: 'ts-1' } as const
    api.createChannel.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'REGISTERING' },
        next: enrollNext,
      })
    )
    // The auto-activate effect treats any next.toolSessionId as "already active" and re-fetches
    // its stepData via the per-tool GET (docs/05-api.md #2) rather than reusing createChannel's own.
    api.getTool.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'REGISTERING' },
        next: enrollNext,
      })
    )
    // Matches the real backend contract: a tool response settling into authenticated still
    // carries no account fields - the client must fetch them explicitly.
    api.patchTool.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'AUTHENTICATED' },
        next: { type: 'orchestrator', context: 'authentication', step: 'authenticated' },
      })
    )
    api.getChannel.mockResolvedValue(
      channelResponse({
        channel: {
          channelSessionId: 'chan-1',
          channelType: 'APP',
          state: 'AUTHENTICATED',
          currentAcr: 'loa1',
          currentAmr: ['sms'],
          activeMethods: [{ id: 'method-1', method: 'sms' }],
        },
        next: { type: 'orchestrator', context: 'authentication', step: 'authenticated' },
      })
    )

    render(<AppChannelApp />)
    const user = userEvent.setup()

    await user.click((await screen.findAllByRole('button', { name: 'Neues Konto anlegen' }))[0])
    // Without demo values the number field starts empty (ADR-28).
    await user.type(await screen.findByLabelText('Telefonnummer'), '+49 170 0000001')
    await user.click(await screen.findByRole('button', { name: 'Code senden' }))

    // Level and methods live on the security screen, one tap from the welcome.
    await user.click(await screen.findByRole('button', { name: /^Sicherheit/ }))
    await screen.findByText('loa1', { selector: '.status-list .value' })
    expect(api.getChannel).toHaveBeenCalledTimes(1)
    // activeMethods backfilled too: the method list leads to a method that can be deactivated.
    await user.click(screen.getByRole('button', { name: /^Anmeldeverfahren/ }))
    await user.click(screen.getByRole('button', { name: /^SMS/ }))
    expect(screen.getByRole('button', { name: 'Deaktivieren' })).toBeInTheDocument()

    // Re-renders after the backfill lands must not trigger a second fetch.
    await waitFor(() => expect(api.getChannel).toHaveBeenCalledTimes(1))
  })

  it('skips the backfill fetch when the channel-level response already carries the fields', async () => {
    // e.g. a step-up that turns out to already be satisfied - raiseRequiredAcr is a genuine
    // channel endpoint, so its own response already includes the account fields.
    window.localStorage.setItem('identity-demo-channel-session-id', 'chan-1')
    api.getChannel.mockResolvedValue(
      channelResponse({
        channel: {
          channelSessionId: 'chan-1',
          channelType: 'APP',
          state: 'AUTHENTICATED',
          currentAcr: 'loa2',
          currentAmr: ['sms', 'password'],
          activeMethods: [
            { id: 'method-1', method: 'sms' },
            { id: 'method-2', method: 'password' },
          ],
        },
        next: { type: 'orchestrator', context: 'authentication', step: 'authenticated' },
      })
    )

    render(<AppChannelApp />)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /Sitzung fortsetzen/ }))

    await user.click(await screen.findByRole('button', { name: /^Sicherheit/ }))
    await screen.findByText('loa2', { selector: '.status-list .value' })
    expect(api.getChannel).toHaveBeenCalledTimes(1) // the resume GET itself - no extra backfill call
  })
})

describe('URL-Einstieg per intent (docs/10-frontend.md #1)', () => {
  it('startet confirm_peer_login automatisch und bereinigt die URL', async () => {
    window.history.replaceState(null, '', '/?intent=confirm_peer_login&pairingCode=AB3D-7KQ2')
    const authNext = { type: 'tool', toolId: 'auth-device', step: 'auth', toolSessionId: 'ts-1' } as const
    api.createChannel.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'STEP_UP_IN_PROGRESS' },
        next: authNext,
      })
    )
    api.getTool.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'STEP_UP_IN_PROGRESS' },
        next: authNext,
      })
    )

    render(<AppChannelApp />)

    await waitFor(() => expect(api.createChannel).toHaveBeenCalledWith(expect.anything(), undefined, 'confirm_peer_login', expect.anything()))
    expect(window.location.search).toBe('')
  })

  it('bestätigt über den bereits authentifizierten Channel statt einen neuen anzulegen', async () => {
    window.localStorage.setItem('identity-demo-channel-session-id', 'chan-1')
    window.history.replaceState(null, '', '/?intent=confirm_peer_login&pairingCode=AB3D-7KQ2')
    api.getChannel.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'AUTHENTICATED', currentAcr: 'loa2' },
        next: { type: 'orchestrator', context: 'authentication', step: 'authenticated' },
      })
    )
    const confirmQrNext = { type: 'tool', toolId: 'confirm-qr-login', step: 'input', toolSessionId: 'ts-2' } as const
    api.startPeerLogin.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'STEP_UP_IN_PROGRESS' },
        next: confirmQrNext,
      })
    )
    api.getTool.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'STEP_UP_IN_PROGRESS' },
        next: confirmQrNext,
      })
    )

    render(<AppChannelApp />)

    await waitFor(() => expect(api.startPeerLogin).toHaveBeenCalledWith(expect.anything(), 'chan-1'))
    expect(api.createChannel).not.toHaveBeenCalled()
  })
})

describe('Back-Button bis zur Startauswahl (docs/10-frontend.md #1)', () => {
  it('verlässt einen laufenden Vorgang lokal, ohne Backend-Aufruf', async () => {
    const identFscNext = { type: 'tool', toolId: 'ident-fsc', step: 'input', toolSessionId: 'ts-1' } as const
    api.createChannel.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'REGISTERING' },
        next: identFscNext,
      })
    )
    api.getTool.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'REGISTERING' },
        next: identFscNext,
      })
    )

    render(<AppChannelApp />)
    const user = userEvent.setup()
    // The phone's own button comes first; the demo column offers the same journey again.
    await user.click((await screen.findAllByRole('button', { name: 'Neues Konto anlegen' }))[0])
    await screen.findByRole('heading', { name: /Freischaltcode/ })

    window.dispatchEvent(new PopStateEvent('popstate', { state: null }))

    // Back on the home screen - as the app shows it for a device without an account.
    await screen.findByRole('heading', { name: 'Willkommen' })
    expect(api.cancelJourney).not.toHaveBeenCalled()
  })
})

describe('gescannter Pairing-Code auf einem Gerät ohne Konto', () => {
  it('bietet nur den Ausweg an und verwirft den Code', async () => {
    window.history.replaceState(null, '', '/?pairingCode=AB3D-7KQ2')
    render(<AppChannelApp />)
    const user = userEvent.setup()

    // Confirming would fail without an account here - so only the way back, not a dead end.
    await screen.findByLabelText('Pairing-Code: AB3D-7KQ2')
    const cancel = await screen.findByRole('button', { name: 'Abbrechen' })
    expect(screen.queryByRole('button', { name: 'Anmeldung bestätigen' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mit E-Mail-Adresse anmelden' })).not.toBeInTheDocument()

    await user.click(cancel)

    // Back on the ordinary start screen, the code dropped.
    await waitFor(() => expect(screen.queryByLabelText('Pairing-Code: AB3D-7KQ2')).not.toBeInTheDocument())
    expect(screen.getAllByRole('button', { name: 'Neues Konto anlegen' }).length).toBeGreaterThan(0)
  })
})

describe('a channel that ends with a tool response', () => {
  it('goes back to the start screen instead of showing an empty phone', async () => {
    const enrollNext = { type: 'tool', toolId: 'enroll-sms', step: 'enroll', toolSessionId: 'ts-1' } as const
    const registering = channelResponse({
      channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'REGISTERING' },
      next: enrollNext,
    })
    api.createChannel.mockResolvedValue(registering)
    api.getTool.mockResolvedValue(registering)
    // Like the last re-proof before an account is deleted: the channel ends, no next step.
    api.patchTool.mockResolvedValue(
      channelResponse({ channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'LOGGED_OUT' } })
    )

    render(<AppChannelApp />)
    const user = userEvent.setup()
    await user.click((await screen.findAllByRole('button', { name: 'Neues Konto anlegen' }))[0])
    // Without demo values the number field starts empty (ADR-28).
    await user.type(await screen.findByLabelText('Telefonnummer'), '+49 170 0000001')
    await user.click(await screen.findByRole('button', { name: 'Code senden' }))

    expect((await screen.findAllByRole('button', { name: 'Neues Konto anlegen' })).length).toBeGreaterThan(0)
  })
})

describe('Abbrechen, bevor etwas nachgewiesen ist', () => {
  it('führt von der Anmeldeauswahl zurück zur Startseite, statt dieselbe Auswahl neu zu beginnen', async () => {
    api.getDeviceLink.mockResolvedValue({ linked: true, accountId: 42 })
    const selection = channelResponse({
      channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'ANONYMOUS', hasProvenFactor: false },
      next: { type: 'orchestrator', context: 'auth', step: 'selectMethod' },
      stepData: { kind: 'select-method', options: ['auth-password-lookup', 'auth-sms-lookup'] },
    })
    api.createChannel.mockResolvedValue(selection)
    // The backend restarts the channel's entry journey on cancel: the very same selection again.
    api.cancelJourney.mockResolvedValue(selection)

    render(<AppChannelApp />)
    const user = userEvent.setup()
    await user.click((await screen.findAllByRole('button', { name: 'Mit E-Mail-Adresse anmelden' }))[0])
    // On the first screen of the journey the way out reads like the registration's: back.
    await user.click(await screen.findByRole('button', { name: 'Zurück' }))

    await screen.findByRole('heading', { name: 'Willkommen zurück' })
    expect(api.cancelJourney).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: 'Abbrechen' })).not.toBeInTheDocument()
  })
})

describe('Weg zur Startseite, solange nicht angemeldet', () => {
  it('führt nach einem ersten Nachweis zur Startseite, statt die Anmeldung neu zu beginnen', async () => {
    api.getDeviceLink.mockResolvedValue({ linked: true, accountId: 42 })
    // One factor proven, a second one required (LOOKUP_LOGIN AdditionalFactor).
    const additionalFactor = channelResponse({
      channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'ANONYMOUS', hasProvenFactor: true },
      next: { type: 'orchestrator', context: 'auth', step: 'selectMethod' },
      stepData: { kind: 'select-method', options: ['auth-sms'] },
    })
    api.createChannel.mockResolvedValue(additionalFactor)
    api.cancelJourney.mockResolvedValue(additionalFactor)

    render(<AppChannelApp />)
    const user = userEvent.setup()
    await user.click((await screen.findAllByRole('button', { name: 'Mit E-Mail-Adresse anmelden' }))[0])
    await user.click(await screen.findByRole('button', { name: 'Abbrechen' }))

    await screen.findByRole('heading', { name: 'Willkommen zurück' })
  })

  it('bietet auch auf einer Rückfrage den Weg zurück', async () => {
    api.getDeviceLink.mockResolvedValue({ linked: true, accountId: 42 })
    api.createChannel.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'ANONYMOUS', hasProvenFactor: false },
        next: { type: 'orchestrator', context: 'prompt', step: 'confirm' },
        stepData: {
          kind: 'confirm',
          // Prompt is open in the contract; the Confirm fields come from types.ts (confirmPromptOf).
          prompt: { kind: 'Confirm', title: { key: 'frage' }, confirmLabel: { key: 'ja' }, cancelLabel: { key: 'nein' } } as Prompt,
        },
      })
    )
    api.cancelJourney.mockResolvedValue(channelResponse({ channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'ANONYMOUS' } }))

    render(<AppChannelApp />)
    const user = userEvent.setup()
    await user.click((await screen.findAllByRole('button', { name: 'Mit E-Mail-Adresse anmelden' }))[0])
    await user.click(await screen.findByRole('button', { name: 'Zurück' }))

    await screen.findByRole('heading', { name: 'Willkommen zurück' })
  })
})

describe('Abbrechen während eines Step-Ups', () => {
  it('bleibt angemeldet und verwirft den Kanal nicht', async () => {
    api.getDeviceLink.mockResolvedValue({ linked: true, accountId: 42 })
    api.createChannel.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'STEP_UP_IN_PROGRESS', hasProvenFactor: true },
        next: { type: 'orchestrator', context: 'auth', step: 'selectMethod' },
        stepData: { kind: 'select-method', options: ['auth-sms'] },
      })
    )
    api.cancelJourney.mockResolvedValue(
      channelResponse({
        channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'AUTHENTICATED', hasProvenFactor: true },
        next: { type: 'orchestrator', context: 'authentication', step: 'authenticated' },
      })
    )

    render(<AppChannelApp />)
    const user = userEvent.setup()
    await user.click((await screen.findAllByRole('button', { name: 'Mit diesem Gerät anmelden' }))[0])
    await user.click(await screen.findByRole('button', { name: 'Abbrechen' }))

    await waitFor(() => expect(api.cancelJourney).toHaveBeenCalledTimes(1))
    // The channel is still the remembered one: a step-up cancel returns to the login, not to the start.
    expect(window.localStorage.getItem('identity-demo-channel-session-id')).toBe('chan-1')
    expect(screen.queryByRole('heading', { name: 'Willkommen zurück' })).not.toBeInTheDocument()
  })
})

describe('Registrierung verwerfen, ohne Demomodus', () => {
  it('fragt nach, sobald das Backend ein Konto im Aufbau meldet (REGISTERING), und endet auf der Startseite', async () => {
    api.getDeviceLink.mockResolvedValue({ linked: false })
    const enrolling = channelResponse({
      // No demo block at all: the question rests on the channel state alone (ADR-46).
      channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'REGISTERING', hasProvenFactor: true },
      next: { type: 'orchestrator', context: 'enrollment', step: 'selectMethod' },
      stepData: { kind: 'select-method', options: ['enroll-sms', 'enroll-password'] },
    })
    api.createChannel.mockResolvedValue(enrolling)
    api.getChannel.mockResolvedValue(enrolling)
    api.cancelJourney.mockResolvedValue(channelResponse({ channel: { channelSessionId: 'chan-1', channelType: 'APP', state: 'ANONYMOUS' } }))

    render(<AppChannelApp />)
    const user = userEvent.setup()
    await user.click((await screen.findAllByRole('button', { name: 'Neues Konto anlegen' }))[0])
    await user.click(await screen.findByRole('button', { name: 'Registrierung verwerfen' }))
    await user.click(await screen.findByRole('button', { name: 'Verwerfen' }))

    await screen.findByRole('heading', { name: 'Willkommen' })
    expect(api.cancelJourney).toHaveBeenCalledTimes(1)
  })
})
