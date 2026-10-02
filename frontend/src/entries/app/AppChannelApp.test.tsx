import { render, screen, waitFor } from '@testing-library/react'
import userEvent, { type UserEvent } from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
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

type Channel = ChannelResponse['channel']

/** Fills in only what applyResponse actually reads; individual tests override per case. */
function channelResponse(overrides: Partial<ChannelResponse> & { channel: Channel }): ChannelResponse {
  return { next: undefined, stepData: undefined, demo: undefined, ...overrides }
}

/** The channel block of the one app channel every test talks to. */
function channel(state: Channel['state'], extra: Partial<Channel> = {}): Channel {
  return { channelSessionId: 'chan-1', channelType: 'APP', state, hasProvenFactor: false, ...extra }
}

function toolNext(toolId: string, step: string, toolSessionId = 'ts-1') {
  return { type: 'tool', toolId, step, toolSessionId } as const
}

const AUTHENTICATED_NEXT = { type: 'orchestrator', context: 'authentication', step: 'authenticated' } as const

/** Taps the phone's own button; the demo column offers the same journeys again further down. */
async function tapFirst(user: UserEvent, name: string) {
  await user.click((await screen.findAllByRole('button', { name }))[0])
}

/** Starts a new account and sends the SMS number; without demo values the field starts empty (ADR-28). */
async function startSmsRegistration(user: UserEvent) {
  await tapFirst(user, 'Neues Konto anlegen')
  await user.type(await screen.findByLabelText('Telefonnummer'), '+49 170 0000001')
  await user.click(await screen.findByRole('button', { name: 'Code senden' }))
}

function rememberChannel() {
  window.localStorage.setItem('identity-demo-channel-session-id', 'chan-1')
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

describe('resume mid-tool (docs/05-api.md #2: next.toolSessionId)', () => {
  it('reuses the running ToolSession instead of reactivating the tool', async () => {
    rememberChannel()
    const resumedNext = toolNext('enroll-sms', 'tanInput', 'ts-resumed')
    api.getChannel.mockResolvedValue(channelResponse({ channel: channel('REGISTERING'), next: resumedNext }))
    // The channel-level GET reports only a bare pointer; the client fetches the tool's stepData
    // separately (ToolControllerSupport.buildReadResponse).
    api.getTool.mockResolvedValue(channelResponse({ channel: channel('REGISTERING'), next: resumedNext, demo: { tan: '123456' } }))
    render(<AppChannelApp />)
    const user = userEvent.setup()

    await user.click(await screen.findByRole('button', { name: /Sitzung fortsetzen/ }))

    // The resumed step (tanInput) renders directly - no second activation round-trip, no second TAN.
    await screen.findByRole('heading', { name: 'TAN eingeben' })
    expect(api.activateTool).not.toHaveBeenCalled()
    // ...but its stepData (here: the demo TAN hint) is re-fetched via the per-tool GET.
    expect(api.getTool).toHaveBeenCalledWith(expect.anything(), 'ts-resumed', 'enroll-sms')
  })
})

describe('security-summary backfill (docs/05-api.md #2: on-demand, not part of tool responses)', () => {
  describe('after a registration that settles into authenticated', () => {
    beforeEach(() => {
      const enrolling = channelResponse({ channel: channel('REGISTERING'), next: toolNext('enroll-sms', 'enroll') })
      api.createChannel.mockResolvedValue(enrolling)
      // The auto-activate effect treats any next.toolSessionId as "already active" and re-fetches
      // its stepData via the per-tool GET (docs/05-api.md #2) rather than reusing createChannel's own.
      api.getTool.mockResolvedValue(enrolling)
      // Matches the real backend contract: a tool response settling into authenticated still
      // carries no account fields - the client must fetch them explicitly.
      api.patchTool.mockResolvedValue(channelResponse({ channel: channel('AUTHENTICATED'), next: AUTHENTICATED_NEXT }))
      api.getChannel.mockResolvedValue(
        channelResponse({
          channel: channel('AUTHENTICATED', { currentAcr: 'loa1', currentAmr: ['sms'], activeMethods: [{ id: 'method-1', method: 'sms' }] }),
          next: AUTHENTICATED_NEXT,
        }),
      )
    })

    it('fetches currentAcr/currentAmr once', async () => {
      render(<AppChannelApp />)
      const user = userEvent.setup()
      await startSmsRegistration(user)

      // Level and methods live on the security screen, one tap from the welcome.
      await user.click(await screen.findByRole('button', { name: /^Sicherheit/ }))

      await screen.findByText('loa1', { selector: '.status-list .value' })
      expect(api.getChannel).toHaveBeenCalledTimes(1)
    })

    it('backfills activeMethods, so the method list leads to a method that can be deactivated', async () => {
      render(<AppChannelApp />)
      const user = userEvent.setup()
      await startSmsRegistration(user)
      await user.click(await screen.findByRole('button', { name: /^Sicherheit/ }))

      await user.click(await screen.findByRole('button', { name: /^Anmeldeverfahren/ }))
      await user.click(screen.getByRole('button', { name: /^SMS/ }))

      expect(screen.getByRole('button', { name: 'Deaktivieren' })).toBeInTheDocument()
    })
  })

  it('skips the backfill fetch when the channel-level response already carries the fields', async () => {
    // e.g. a step-up that turns out to already be satisfied - raiseRequiredAcr is a genuine
    // channel endpoint, so its own response already includes the account fields.
    rememberChannel()
    api.getChannel.mockResolvedValue(
      channelResponse({
        channel: channel('AUTHENTICATED', {
          currentAcr: 'loa2',
          currentAmr: ['sms', 'password'],
          activeMethods: [
            { id: 'method-1', method: 'sms' },
            { id: 'method-2', method: 'password' },
          ],
        }),
        next: AUTHENTICATED_NEXT,
      }),
    )
    render(<AppChannelApp />)
    const user = userEvent.setup()
    await user.click(await screen.findByRole('button', { name: /Sitzung fortsetzen/ }))

    await user.click(await screen.findByRole('button', { name: /^Sicherheit/ }))

    await screen.findByText('loa2', { selector: '.status-list .value' })
    expect(api.getChannel).toHaveBeenCalledTimes(1) // the resume GET itself - no extra backfill call
  })
})

describe('entry by URL intent (docs/10-frontend.md #1)', () => {
  it('starts confirm_peer_login on its own and clears the URL', async () => {
    window.history.replaceState(null, '', '/?intent=confirm_peer_login&pairingCode=AB3D-7KQ2')
    const stepUp = channelResponse({ channel: channel('STEP_UP_IN_PROGRESS'), next: toolNext('auth-device', 'auth') })
    api.createChannel.mockResolvedValue(stepUp)
    api.getTool.mockResolvedValue(stepUp)

    render(<AppChannelApp />)

    await waitFor(() => expect(api.createChannel).toHaveBeenCalledWith(expect.anything(), undefined, 'confirm_peer_login', expect.anything()))
    expect(window.location.search).toBe('')
  })

  it('confirms over the channel already authenticated instead of creating a new one', async () => {
    rememberChannel()
    window.history.replaceState(null, '', '/?intent=confirm_peer_login&pairingCode=AB3D-7KQ2')
    api.getChannel.mockResolvedValue(channelResponse({ channel: channel('AUTHENTICATED', { currentAcr: 'loa2' }), next: AUTHENTICATED_NEXT }))
    const confirming = channelResponse({ channel: channel('STEP_UP_IN_PROGRESS'), next: toolNext('approve-qr', 'input', 'ts-2') })
    api.startPeerLogin.mockResolvedValue(confirming)
    api.getTool.mockResolvedValue(confirming)

    render(<AppChannelApp />)

    await waitFor(() => expect(api.startPeerLogin).toHaveBeenCalledWith(expect.anything(), 'chan-1'))
    expect(api.createChannel).not.toHaveBeenCalled()
  })
})

describe('browser back up to the start choice (docs/10-frontend.md #1)', () => {
  it('leaves a running journey locally, without a backend call', async () => {
    const identifying = channelResponse({ channel: channel('REGISTERING'), next: toolNext('ident-fsc', 'input') })
    api.createChannel.mockResolvedValue(identifying)
    api.getTool.mockResolvedValue(identifying)
    render(<AppChannelApp />)
    const user = userEvent.setup()
    await tapFirst(user, 'Neues Konto anlegen')
    await screen.findByRole('heading', { name: /Freischaltcode/ })

    window.dispatchEvent(new PopStateEvent('popstate', { state: null }))

    // Back on the home screen - as the app shows it for a device without an account.
    await screen.findByRole('heading', { name: 'Willkommen' })
    expect(api.cancelJourney).not.toHaveBeenCalled()
  })
})

describe('a scanned pairing code on a device without an account', () => {
  beforeEach(() => {
    window.history.replaceState(null, '', '/?pairingCode=AB3D-7KQ2')
  })

  it('offers only the way back', async () => {
    render(<AppChannelApp />)

    // Confirming would fail without an account here - so only the way back, not a dead end.
    await screen.findByLabelText('Pairing-Code: AB3D-7KQ2')
    expect(await screen.findByRole('button', { name: 'Abbrechen' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Anmeldung bestätigen' })).not.toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Mit E-Mail-Adresse anmelden' })).not.toBeInTheDocument()
  })

  it('drops the code on cancel and shows the ordinary start screen', async () => {
    render(<AppChannelApp />)
    const user = userEvent.setup()
    await screen.findByLabelText('Pairing-Code: AB3D-7KQ2')

    await user.click(await screen.findByRole('button', { name: 'Abbrechen' }))

    await waitFor(() => expect(screen.queryByLabelText('Pairing-Code: AB3D-7KQ2')).not.toBeInTheDocument())
    expect(screen.getAllByRole('button', { name: 'Neues Konto anlegen' }).length).toBeGreaterThan(0)
  })
})

describe('a channel that ends with a tool response', () => {
  it('goes back to the start screen instead of showing an empty phone', async () => {
    const enrolling = channelResponse({ channel: channel('REGISTERING'), next: toolNext('enroll-sms', 'enroll') })
    api.createChannel.mockResolvedValue(enrolling)
    api.getTool.mockResolvedValue(enrolling)
    // Like the last re-proof before an account is deleted: the channel ends, no next step.
    api.patchTool.mockResolvedValue(channelResponse({ channel: channel('LOGGED_OUT') }))
    render(<AppChannelApp />)
    const user = userEvent.setup()

    await startSmsRegistration(user)

    expect((await screen.findAllByRole('button', { name: 'Neues Konto anlegen' })).length).toBeGreaterThan(0)
  })
})

describe('the way out before anything is proven', () => {
  it('leads from the login choice back to the start instead of beginning the same choice again', async () => {
    api.getDeviceLink.mockResolvedValue({ linked: true, accountId: 42 })
    const selection = channelResponse({
      channel: channel('ANONYMOUS', { hasProvenFactor: false }),
      next: { type: 'orchestrator', context: 'auth', step: 'selectMethod' },
      stepData: { kind: 'select-method', options: ['auth-password-lookup', 'auth-sms-lookup'] },
    })
    api.createChannel.mockResolvedValue(selection)
    // The backend restarts the channel's entry journey on cancel: the very same selection again.
    api.cancelJourney.mockResolvedValue(selection)
    render(<AppChannelApp />)
    const user = userEvent.setup()
    await tapFirst(user, 'Mit E-Mail-Adresse anmelden')

    // On the first screen of the journey the way out reads like the registration's: back.
    await user.click(await screen.findByRole('button', { name: 'Zurück' }))

    await screen.findByRole('heading', { name: 'Willkommen zurück' })
    expect(api.cancelJourney).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: 'Abbrechen' })).not.toBeInTheDocument()
  })

  it('leads from a question back to the start as well', async () => {
    api.getDeviceLink.mockResolvedValue({ linked: true, accountId: 42 })
    api.createChannel.mockResolvedValue(
      channelResponse({
        channel: channel('ANONYMOUS', { hasProvenFactor: false }),
        next: { type: 'orchestrator', context: 'prompt', step: 'confirm' },
        stepData: {
          kind: 'confirm',
          // Prompt is open in the contract; the Confirm fields come from types.ts (confirmPromptOf).
          prompt: { kind: 'Confirm', title: { key: 'frage' }, confirmLabel: { key: 'ja' }, cancelLabel: { key: 'nein' } } as Prompt,
        },
      }),
    )
    api.cancelJourney.mockResolvedValue(channelResponse({ channel: channel('ANONYMOUS') }))
    render(<AppChannelApp />)
    const user = userEvent.setup()
    await tapFirst(user, 'Mit E-Mail-Adresse anmelden')

    await user.click(await screen.findByRole('button', { name: 'Zurück' }))

    await screen.findByRole('heading', { name: 'Willkommen zurück' })
    expect(api.cancelJourney).toHaveBeenCalledTimes(1)
  })

  // Leaving a registration before anything is proven throws nothing away - so there is no
  // "really discard?" question, and on the very first screen the way out is a plain "Zurück".
  it('leads from the first registration screen straight back to the start, without asking', async () => {
    api.createChannel.mockResolvedValue(
      channelResponse({
        channel: channel('REGISTERING', { hasProvenFactor: false }),
        next: { type: 'orchestrator', context: 'identification', step: 'selectMethod' },
        stepData: { kind: 'select-method', options: ['ident-fsc', 'ident-eid'] },
      }),
    )
    api.cancelJourney.mockResolvedValue(channelResponse({ channel: channel('ANONYMOUS') }))
    render(<AppChannelApp />)
    const user = userEvent.setup()
    await tapFirst(user, 'Neues Konto anlegen')

    await user.click(await screen.findByRole('button', { name: 'Zurück' }))

    await screen.findByRole('heading', { name: 'Willkommen' })
    expect(screen.queryByRole('button', { name: 'Verwerfen' })).not.toBeInTheDocument()
    expect(api.cancelJourney).toHaveBeenCalledTimes(1)
  })
})

describe('the way out once a first factor is proven, while not logged in', () => {
  it('leads to the start instead of beginning the login again', async () => {
    api.getDeviceLink.mockResolvedValue({ linked: true, accountId: 42 })
    // One factor proven, a second one required (LOOKUP_LOGIN AdditionalFactor).
    const additionalFactor = channelResponse({
      channel: channel('ANONYMOUS', { hasProvenFactor: true }),
      next: { type: 'orchestrator', context: 'auth', step: 'selectMethod' },
      stepData: { kind: 'select-method', options: ['auth-sms'] },
    })
    api.createChannel.mockResolvedValue(additionalFactor)
    api.cancelJourney.mockResolvedValue(additionalFactor)
    render(<AppChannelApp />)
    const user = userEvent.setup()
    await tapFirst(user, 'Mit E-Mail-Adresse anmelden')

    await user.click(await screen.findByRole('button', { name: 'Abbrechen' }))

    await screen.findByRole('heading', { name: 'Willkommen zurück' })
    expect(api.cancelJourney).toHaveBeenCalledTimes(1)
  })
})

describe('cancelling a step-up', () => {
  it('stays logged in and keeps the channel', async () => {
    api.getDeviceLink.mockResolvedValue({ linked: true, accountId: 42 })
    api.createChannel.mockResolvedValue(
      channelResponse({
        channel: channel('STEP_UP_IN_PROGRESS', { hasProvenFactor: true }),
        next: { type: 'orchestrator', context: 'auth', step: 'selectMethod' },
        stepData: { kind: 'select-method', options: ['auth-sms'] },
      }),
    )
    api.cancelJourney.mockResolvedValue(channelResponse({ channel: channel('AUTHENTICATED', { hasProvenFactor: true }), next: AUTHENTICATED_NEXT }))
    render(<AppChannelApp />)
    const user = userEvent.setup()
    await tapFirst(user, 'Mit diesem Gerät anmelden')

    await user.click(await screen.findByRole('button', { name: 'Abbrechen' }))

    await waitFor(() => expect(api.cancelJourney).toHaveBeenCalledTimes(1))
    // The channel is still the remembered one: a step-up cancel returns to the login, not to the start.
    expect(window.localStorage.getItem('identity-demo-channel-session-id')).toBe('chan-1')
    expect(screen.queryByRole('heading', { name: 'Willkommen zurück' })).not.toBeInTheDocument()
  })
})

describe('discarding a registration, without demo mode', () => {
  it('asks once the backend reports an account under construction (REGISTERING) and ends on the start screen', async () => {
    const enrolling = channelResponse({
      // No demo block at all: the question rests on the channel state alone (ADR-46).
      channel: channel('REGISTERING', { hasProvenFactor: true }),
      next: { type: 'orchestrator', context: 'enrollment', step: 'selectMethod' },
      stepData: { kind: 'select-method', options: ['enroll-sms', 'enroll-password'] },
    })
    api.createChannel.mockResolvedValue(enrolling)
    api.getChannel.mockResolvedValue(enrolling)
    api.cancelJourney.mockResolvedValue(channelResponse({ channel: channel('ANONYMOUS') }))
    render(<AppChannelApp />)
    const user = userEvent.setup()
    await tapFirst(user, 'Neues Konto anlegen')
    await user.click(await screen.findByRole('button', { name: 'Registrierung verwerfen' }))

    await user.click(await screen.findByRole('button', { name: 'Verwerfen' }))

    await screen.findByRole('heading', { name: 'Willkommen' })
    expect(api.cancelJourney).toHaveBeenCalledTimes(1)
  })
})
