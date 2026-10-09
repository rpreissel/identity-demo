import { resolveText, t } from '../../texts'
import { Tx } from '../../Tx'
import { useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { computeJwkThumbprint, getOrCreateDpopKeyPair, resetDpopKeyPair, type DpopKeyPair } from '../../dpop.ts'
import '../../App.css'
import '../../phone.css'
import type { ActiveMethodView, ChannelResponse, DemoInfo, DeviceLinkResponse, Next, StepData } from '../../types'
import { confirmPromptOf, stepDataOf } from '../../types'
import { getUIComponent } from '../../routing.ts'
import { enrollmentToolFor, explainToolStep, knownToolIds, metaFor, renderToolStep } from '../../tools/registry'
import type { ToolRenderContext } from '../../tools/types'
import {
  abandonTool,
  activateTool,
  backFromTool,
  answerPrompt,
  cancelJourney,
  createChannel,
  deactivateMethod,
  describeError,
  getChannel,
  getDeviceLink,
  getTool,
  startLogout,
  onApiCall,
  raiseRequiredAcr,
  startAccountDeletion,
  changeMethod,
  startManageMethods,
  startPeerLogin,
} from '../../api.ts'
import {
  forgetChannelSessionId,
  forgetPendingPairingCode,
  loadAvailableTools,
  loadChannelSessionId,
  loadPendingPairingCode,
  storeAvailableTools,
  storeChannelSessionId,
  storePendingPairingCode,
} from '../../session.ts'
import { storeReturnedNectCase } from '../../tools/nect/returnedCase'
import { dropStaleKobilData } from '../../tools/kobil/localData'
import { shorten } from '../../format.ts'
import { ChannelNav } from '../../components/ChannelNav'
import { Demo, DemoAction, DemoArea, DemoProvider } from '../../components/DemoArea'
import { PhoneFrame, StepNav } from '../../components/PhoneFrame'
import { InnerBackProvider, type InnerBackRegistry } from '../../components/InnerBack'
import { AuthenticationCompletedView, type AccountView } from '../../components/AuthenticationCompletedView'
import { CodeDisplay } from '../../components/CodeDisplay'
import { StepExplanation } from '../../components/StepExplanation'
import { SessionSummary } from '../../components/SessionSummary'
import { ButtonDiagrams } from '../../components/ButtonDiagrams'
import { DebugSidebar, type DebugEvent } from '../../components/DebugSidebar'
import { SelectMethodView } from '../../components/SelectMethodView'
import { JourneyStructureView } from '../../components/JourneyStructureView'
import { PromptView } from '../../components/PromptView'
import { ToolAvailabilitySelector } from '../../components/ToolAvailabilitySelector'
import { UnavailableTools } from '../../components/UnavailableTools'
import { Disclosure } from '../../components/Disclosure'
import { DeviceIdentityCard } from '../../components/DeviceIdentityCard'
import { DiagramTrigger } from '../../components/DiagramHint'
import { CURRENT_STEP_BY_STATE_TYPE, currentJourneyDiagramKey, journeyContextLabel, JOURNEY_DIAGRAMS } from '../../journeyDiagrams'

interface ActiveTool {
  toolSessionId: string
  toolId: string
}


/**
 * Wire vocabulary of AuthIntent's entry intents (backend `AuthIntent.fromRequest`) mapped to
 * `handleStart`'s mode names. Only intents this app can enter cold from a URL.
 */
const INTENT_TO_START_MODE: Record<string, 'auto' | 'login' | 'register' | 'confirmPeerLogin'> = {
  fast_access: 'auto',
  lookup_login: 'login',
  register: 'register',
  confirm_peer_login: 'confirmPeerLogin',
}

/**
 * The rows of an enrollment choice: in the order first shown in this channel, the backend's options
 * and the methods already set up. A set-up method is marked in its row, whether the backend offers
 * it again (the device method, for another device) or not.
 */
function enrollmentChoiceRows(order: string[], options: string[], setUp: { account: string[]; device: string[] }) {
  const has = (methods: string[], toolId: string) => {
    const method = metaFor(toolId).enrolls
    return method !== undefined && methods.includes(method)
  }
  const accountTools = setUp.account.map(enrollmentToolFor).filter((toolId): toolId is string => toolId !== undefined)
  const rows = [...new Set([...order, ...options, ...accountTools])].filter(
    (toolId) => options.includes(toolId) || has(setUp.account, toolId),
  )
  return {
    rows,
    setUp: rows.filter((toolId) => has(setUp.device, toolId) || (!options.includes(toolId) && has(setUp.account, toolId))),
  }
}

export function AppChannelApp() {
  const [dpop, setDpop] = useState<DpopKeyPair | null>(null)
  const [jwkThumbprint, setJwkThumbprint] = useState<string | undefined>()
  // Whose device this is (DeviceAccountLink), shown on DeviceIdentityCard. null means "still loading",
  // distinct from an answered "not linked" (linked === false).
  const [deviceLink, setDeviceLink] = useState<DeviceLinkResponse | null>(null)
  const [channelSessionId, setChannelSessionId] = useState<string | undefined>()
  const [channelState, setChannelState] = useState<string | undefined>()
  const [currentAcr, setCurrentAcr] = useState<string | undefined>()
  const [currentAmr, setCurrentAmr] = useState<string[] | undefined>()
  const [activeMethods, setActiveMethods] = useState<ActiveMethodView[] | undefined>()
  const [next, setNext] = useState<Next | undefined>()
  const [stepData, setStepData] = useState<StepData | undefined>()
  // The orchestrator's `message` step, kept across auto-activating the one tool it announces -
  // that tool's own response replaces stepData and would otherwise swallow it.
  const [carriedMessage, setCarriedMessage] = useState<string | undefined>()
  // What to say once a self-service journey this app started has run through, e.g. a changed
  // method. Dropped when the user backs out; shown on the screen the journey returns to.
  const pendingOutcomeNoticeRef = useRef<string | undefined>(undefined)
  const [outcomeNotice, setOutcomeNotice] = useState<string | undefined>()
  const [demo, setDemo] = useState<DemoInfo | undefined>()
  const [activeTool, setActiveTool] = useState<ActiveTool | null>(null)
  // Set from the WEB channel's demo link (?pairingCode=..., docs/verfahren/qr.md), which points
  // straight at this app's /app/ entry. Shown as a banner near the entry choice and drives the
  // auto-start effect below.
  const [pendingPairingCode, setPendingPairingCode] = useState<string | undefined>()
  // Gates "Zurück": not whether another candidate existed, only whether the button is worth showing.
  // Abandoning is always a safe backend fallback. >0 whenever activeTool is set. A resumed or
  // single-candidate step counts as 1, otherwise an AUTHENTICATED channel's step-up has no way out.
  const [alternativesCount, setAlternativesCount] = useState(0)
  // Rückfrage vor dem Verwerfen einer Registrierung: Abbrechen beendet die ganze Journey. Der Kanal
  // fällt auf ANONYMOUS zurück, und das vorläufige Konto wird samt einer schon enthaltenen
  // eID-Bezeugung gelöscht. Das darf kein Klick nebenbei sein.
  const [confirmingDiscard, setConfirmingDiscard] = useState(false)
  // Whether this session has already proven something (backend ChannelBlock.hasProvenFactor) -
  // only then does leaving a registration throw anything away, and only then is it worth asking.
  const [hasProvenFactor, setHasProvenFactor] = useState(false)
  // "Dieses Gerät zurücksetzen" on the start screen asks once - it forgets this device's key.
  const [confirmingReset, setConfirmingReset] = useState(false)
  const [error, setError] = useState('')
  // A tool's own previous screen (e.g. confirm-email: code back to address) - pure UI, no server call.
  const [innerBack, setInnerBack] = useState<(() => void) | null>(null)
  const innerBackRegistry = useMemo<InnerBackRegistry>(() => ({ set: (handler) => setInnerBack(() => handler) }), [])
  // The logged-in screen (welcome, profile, security) - kept here so a step-up or an added method
  // returns to where it was started; a new channel starts at the welcome again.
  const [accountView, setAccountView] = useState<AccountView>('home')
  useEffect(() => setAccountView('home'), [channelSessionId])
  // The rows of this channel's enrollment choice in the order first shown, and what the account
  // and this device already have. A method set up in between keeps its row, marked as set up, so
  // the rows below it do not move up under the finger.
  const [enrollmentOrder, setEnrollmentOrder] = useState<string[]>([])
  const [setUpMethods, setSetUpMethods] = useState<{ account: string[]; device: string[] }>({ account: [], device: [] })
  // Takes effect on the next channel-creating action. Needed to reach enroll-password: it requires a
  // confirmed email, but a single loa1 method already satisfies the default floor and ends
  // registration. Requesting loa2 up front keeps the flow going to chain sms/email -> password.
  const [requiredAcr, setRequiredAcr] = useState('')
  // Client capability declaration (docs/03-tool-architektur.md, availability) - starts as
  // "everything this client can render" and is only narrowed by unchecking in the demo selector.
  // Remembered in localStorage (session.ts) so the choice survives a reload.
  const [availableTools, setAvailableToolsState] = useState<string[]>(() => loadAvailableTools() ?? knownToolIds)

  function setAvailableTools(toolIds: string[]) {
    setAvailableToolsState(toolIds)
    storeAvailableTools(toolIds)
  }
  const [debugLog, setDebugLog] = useState<DebugEvent[]>([])
  const debugIdRef = useRef(0)
  // Drives the "Sitzung fortsetzen" button on the "no channel" screen. Kept in sync explicitly
  // rather than derived from channelSessionId: it must reflect localStorage after Clear/Logout.
  const [rememberedChannelSessionId, setRememberedChannelSessionId] = useState(() => loadChannelSessionId())

  function logEvent(label: string, extra?: { request?: unknown; response?: unknown; error?: string }) {
    debugIdRef.current += 1
    setDebugLog((prev) => [{ id: debugIdRef.current, time: new Date().toLocaleTimeString(), label, ...extra }, ...prev].slice(0, 200))
  }

  // Single source of truth for the debug log's API entries: every call() in api.ts reports here.
  useEffect(() => {
    return onApiCall((entry) => {
      logEvent(`${entry.method} ${entry.path}`, { request: entry.requestBody, response: entry.responseBody, error: entry.error })
    })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  function clearChannelState() {
    setChannelSessionId(undefined)
    setChannelState(undefined)
    setHasProvenFactor(false)
    setCurrentAcr(undefined)
    setCurrentAmr(undefined)
    setActiveMethods(undefined)
    setNext(undefined)
    setStepData(undefined)
    setDemo(undefined)
    setActiveTool(null)
    setAlternativesCount(0)
    setEnrollmentOrder([])
  }

  /**
   * The apply-function for every endpoint's response (docs/05-api.md #1: unified envelope).
   * `currentAcr`/`currentAmr`/`activeMethods` are the exception: tool responses never carry them,
   * the effect below fetches them when the security-summary screen is reached.
   *
   * [actedToolId] is the tool just acted on. `next.toolSessionId` tells whether it is still the
   * active tool or the response handed off to another one (e.g. ident-fsc -> auth-sms). Pairing
   * the wrong toolId would stop the auto-activate effect from activating the new tool.
   */
  function applyResponse(response: ChannelResponse, actedToolId?: string) {
    // A channel that ended (logout, deleted account) has no next step: back to the start screen,
    // whichever response ended it - a tool completion as much as an answered question.
    if (!response.next && (response.channel.state === 'LOGGED_OUT' || response.channel.state === 'EXPIRED')) {
      handleClearChannel()
      return
    }
    // Every response that got this far is a success, so a banner from an earlier rejected step
    // (e.g. an address that belonged to somebody else) must go.
    setError('')
    // Eine offene Verwerfen-Rückfrage gehört zu dem Schritt, auf dem sie gestellt wurde.
    setConfirmingDiscard(false)
    setChannelSessionId(response.channel.channelSessionId)
    storeChannelSessionId(response.channel.channelSessionId)
    setRememberedChannelSessionId(response.channel.channelSessionId)
    setChannelState(response.channel.state)
    setHasProvenFactor(response.channel.hasProvenFactor ?? false)
    setCurrentAcr(response.channel.currentAcr)
    setCurrentAmr(response.channel.currentAmr)
    setActiveMethods(response.channel.activeMethods)
    setNext(response.next)
    setStepData(response.stepData)
    setCarriedMessage(undefined)
    const journeyOver = response.next?.type === 'orchestrator' && response.next.context === 'authentication' && response.next.step === 'authenticated'
    setOutcomeNotice(journeyOver ? pendingOutcomeNoticeRef.current : undefined)
    if (journeyOver) pendingOutcomeNoticeRef.current = undefined
    setDemo(response.demo)
    const offered = stepDataOf(response.stepData, 'select-method')?.options
    if (offered && response.next?.type === 'orchestrator' && response.next.context === 'enrollment') {
      setEnrollmentOrder((order) => [...order, ...offered.filter((toolId) => !order.includes(toolId))])
    }

    const next = response.next
    if (actedToolId && next?.type === 'tool' && next.toolId === actedToolId && next.toolSessionId) {
      setActiveTool({ toolSessionId: next.toolSessionId, toolId: actedToolId })
    } else {
      setActiveTool(null)
    }
  }

  // Bootstrap: only the DPoP key pair and its thumbprint. The user explicitly chooses how to start
  // (docs/02-domaenenmodell.md #3). The DPoP key proves which device this is, but it is never a
  // lookup key for resuming a session.
  useEffect(() => {
    let active = true
    async function init() {
      const keyPair = await getOrCreateDpopKeyPair()
      if (!active) return
      setDpop(keyPair)
      const thumbprint = await computeJwkThumbprint(keyPair.publicJwk)
      if (!active) return
      setJwkThumbprint(thumbprint)
      logEvent('DPoP-Key geladen/erzeugt', { response: { jwkThumbprint: thumbprint, publicJwk: keyPair.publicJwk } })
    }
    init().catch((err) => setError(describeError(t('Start der App fehlgeschlagen'), err)))
    return () => {
      active = false
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Refetches whenever the entry screen shows and a key is ready: first mount, back to start, a
  // recreated key. Not fetched while a channel is active; DeviceIdentityCard keeps its last answer.
  useEffect(() => {
    if (!dpop || channelSessionId) return
    let active = true
    getDeviceLink(dpop)
      .then((link) => {
        if (!active) return
        setDeviceLink(link)
        // Local data whose binding is gone has to go with it - the rule itself belongs to the
        // owning tool module, not here.
        dropStaleKobilData(link.boundCredentials)
      })
      .catch(() => {
        // Non-fatal - DeviceIdentityCard just shows "…" a bit longer, no error banner for this.
      })
    return () => {
      active = false
    }
  }, [dpop, channelSessionId])

  /**
   * URL entry point (docs/10-frontend.md #6, FE-18): reads `intent` (AuthIntent's wire vocabulary)
   * and `pairingCode` once `dpop` is ready, then strips both from the URL. A ref guards against
   * StrictMode's double invocation.
   *
   * `confirm_peer_login` first loads a channel this device remembers. If it is already
   * AUTHENTICATED, `handlePeerLogin` runs on it instead of forcing a full re-login in a new channel.
   * Which proof that requires is the server's call (docs/04-orchestrierung.md CONFIRM_PEER_LOGIN).
   */
  const urlEntryHandledRef = useRef(false)
  useEffect(() => {
    if (!dpop || urlEntryHandledRef.current) return
    const params = new URLSearchParams(window.location.search)
    const intentParam = params.get('intent')
    const pairingCode = params.get('pairingCode')
    const nectCaseId = params.get('nectCaseId')
    if (!intentParam && !pairingCode && !nectCaseId) return
    urlEntryHandledRef.current = true

    if (pairingCode) {
      setPendingPairingCode(pairingCode)
      storePendingPairingCode(pairingCode)
      logEvent('QR-Pairing-Code aus Link übernommen', { response: { pairingCode } })
    }

    params.delete('intent')
    params.delete('pairingCode')
    params.delete('nectCaseId')
    const query = params.toString()
    window.history.replaceState(null, '', window.location.pathname + (query ? `?${query}` : '') + window.location.hash)

    // Back from Nect's jump page (ident-nect): the channel waiting for this case is the one this
    // device remembers - resume it, and the tool's redirect step reports the case itself.
    if (nectCaseId) {
      storeReturnedNectCase(nectCaseId)
      logEvent('Rücksprung von Nect', { response: { nectCaseId } })
      handleStart('resume')
      return
    }

    const mode = intentParam ? INTENT_TO_START_MODE[intentParam.toLowerCase()] : undefined
    if (!mode) return

    if (mode !== 'confirmPeerLogin') {
      handleStart(mode)
      return
    }

    const rememberedId = loadChannelSessionId()
    if (!rememberedId) {
      handleStart('confirmPeerLogin')
      return
    }
    getChannel(dpop, rememberedId)
      .then((response) => {
        if (response.channel.state !== 'AUTHENTICATED') {
          forgetChannelSessionId()
          setRememberedChannelSessionId(null)
          return handleStart('confirmPeerLogin')
        }
        applyResponse(response)
        // Not handlePeerLogin(): its closure still sees channelSessionId as undefined, because the
        // setState above has not committed yet. A failure here (e.g. the loa2 gate) goes the normal
        // error path; the outer catch would discard the just-authenticated channel.
        return startPeerLogin(dpop, response.channel.channelSessionId)
          .then((peerResponse) => applyResponse(peerResponse))
          .catch((err) => setError(describeError(t('Web-Login-Bestätigung fehlgeschlagen'), err)))
      })
      .catch(() => {
        forgetChannelSessionId()
        setRememberedChannelSessionId(null)
        return handleStart('confirmPeerLogin')
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dpop])

  /**
   * Back-button support: leave the running process and land on the start choice (docs/
   * 10-frontend.md #6). Not a step-by-step undo; the `next` chain is forward-only.
   * `channelActiveRef` mirrors `channelSessionId` so the popstate listener reads the current value.
   */
  const channelActiveRef = useRef(false)
  useEffect(() => {
    const wasActive = channelActiveRef.current
    channelActiveRef.current = !!channelSessionId
    if (!wasActive && channelSessionId) {
      window.history.pushState({ identityDemoChannel: true }, '', window.location.href)
    }
  }, [channelSessionId])

  useEffect(() => {
    function onPopState(event: PopStateEvent) {
      const state = event.state as { identityDemoChannel?: boolean } | null
      if (!state?.identityDemoChannel && channelActiveRef.current) {
        handleClearChannel()
      }
    }
    window.addEventListener('popstate', onPopState)
    return () => window.removeEventListener('popstate', onPopState)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [])

  // Auto-activate whenever `next` points at a tool we haven't activated yet. activatingToolIdRef
  // guards against StrictMode's double invocation: refs update synchronously, so the second run
  // sees the in-flight marker before it fires a second POST.
  const activatingToolIdRef = useRef<string | null>(null)
  useEffect(() => {
    if (!dpop || !channelSessionId || !next || next.type !== 'tool' || !next.toolId) return
    if (activeTool?.toolId === next.toolId) return
    if (activatingToolIdRef.current === next.toolId) return

    // A resumed process already has a running ToolSession for this step (docs/05-api.md #1:
    // next.toolSessionId). Activating again would start a new attempt, e.g. a second TAN.
    if (next.toolSessionId) {
      // Resuming: whether alternatives existed is lost, but abandoning is always a safe backend
      // fallback. Hiding "Zurück" would leave an AUTHENTICATED channel's step-up without a way out.
      setAlternativesCount(1)
      setActiveTool({ toolSessionId: next.toolSessionId, toolId: next.toolId })
    // The channel-level GET only reports a bare pointer. missingFields and demo hints come from
    // the tool's own responses and would be lost on resume, so fetch the tool's read-back
    // (GET /tools/api/{toolId}/v{N}/{id}).
      const toolId = next.toolId
      const toolSessionId = next.toolSessionId
      activatingToolIdRef.current = toolId
      getTool(dpop, toolSessionId, toolId)
        .then((response) => applyResponse(response, toolId))
        .catch((err) => setError(describeError(t('Stand des Verfahrens konnte nicht geladen werden'), err)))
        .finally(() => {
          if (activatingToolIdRef.current === toolId) activatingToolIdRef.current = null
        })
      return
    }

    const toolId = next.toolId
    // A single candidate offered directly (e.g. the linked device) does not mean there is no
    // alternative. Abandoning stays a normal backend fallback, so "Zurück" stays offered.
    setAlternativesCount(1)
    activatingToolIdRef.current = toolId
    const pendingText = stepDataOf(stepData, 'message')?.message
    const pendingMessage = pendingText ? resolveText(pendingText) : undefined
    activateTool(dpop, channelSessionId, toolId, activationBodyFor(toolId))
      .then((response) => {
        // Keep the journey-level message (e.g. "E-Mail-Bestätigung ausstehend") across
        // auto-activation, since the tool's response carries its own step. Kept beside stepData:
        // merged into another step's shape it would be neither of them.
        applyResponse(response, toolId)
        setCarriedMessage(pendingMessage)
      })
      .catch((err) => setError(describeError(t('Verfahren konnte nicht gestartet werden'), err)))
      .finally(() => {
        if (activatingToolIdRef.current === toolId) activatingToolIdRef.current = null
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dpop, channelSessionId, next, activeTool])

  /**
   * Fetches currentAcr/currentAmr/activeMethods when the security-summary screen is reached; tool
   * responses never carry them (docs/05-api.md #1). applyResponse clears `currentAcr` on every tool
   * response, so `undefined` reliably means "not yet loaded for the current state".
   * loadingSecurityDetailsRef guards against StrictMode's double invocation, whose concurrent
   * getChannel() could fail with CONCURRENT_MODIFICATION.
   */
  const loadingSecurityDetailsRef = useRef(false)
  useEffect(() => {
    if (!dpop || !channelSessionId) return
    if (next?.type !== 'orchestrator' || next.context !== 'authentication' || next.step !== 'authenticated') return
    if (currentAcr !== undefined) return
    if (loadingSecurityDetailsRef.current) return
    loadingSecurityDetailsRef.current = true

    getChannel(dpop, channelSessionId)
      .then((response) => {
        setCurrentAcr(response.channel.currentAcr)
        setCurrentAmr(response.channel.currentAmr)
        setActiveMethods(response.channel.activeMethods)
      })
      .catch((err) => setError(describeError(t('Sicherheitsdetails laden fehlgeschlagen'), err)))
      .finally(() => {
        loadingSecurityDetailsRef.current = false
      })
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [dpop, channelSessionId, next, currentAcr])

  /**
   * What the enrollment choice marks as set up: the account's methods (tool responses do not carry
   * them) and the bindings of this device, which a step earlier in this channel may have added.
   */
  const enrollmentChoice = next?.type === 'orchestrator' && next.context === 'enrollment'
  useEffect(() => {
    if (!dpop || !channelSessionId || !enrollmentChoice) return
    let active = true
    Promise.all([getChannel(dpop, channelSessionId), getDeviceLink(dpop)])
      .then(([channel, link]) => {
        if (!active) return
        setDeviceLink(link)
        setSetUpMethods({
          account: (channel.channel.activeMethods ?? []).map((method) => method.method),
          device: (link.boundCredentials ?? []).map((credential) => credential.method),
        })
      })
      .catch(() => {
        // Non-fatal - the choice then only shows what the backend offers.
      })
    return () => {
      active = false
    }
  }, [dpop, channelSessionId, enrollmentChoice, next])

  /**
   * Every explicit way a channel comes into existence: "resume" reads a remembered
   * channelSessionId, the others create a new channel with the matching `intent`. "auto" omits it
   * (DeviceAccountLink found -> LOGIN, else REGISTRATION).
   */
  async function handleStart(mode: 'resume' | 'auto' | 'login' | 'register' | 'confirmPeerLogin') {
    if (!dpop) return
    try {
      setError('')
      if (mode === 'resume') {
        const rememberedId = loadChannelSessionId()
        if (!rememberedId) return
        try {
          const response = await getChannel(dpop, rememberedId)
          applyResponse(response)
        } catch (err) {
          forgetChannelSessionId()
          setRememberedChannelSessionId(null)
          throw err
        }
        return
      }
      const intent =
        mode === 'auto' ? undefined : mode === 'login' ? 'lookup_login' : mode === 'confirmPeerLogin' ? 'confirm_peer_login' : mode
      const response = await createChannel(dpop, requiredAcr || undefined, intent, availableTools)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Start fehlgeschlagen'), err))
    }
  }

  /** "Abbrechen" on the scanned-code screen: the code is dropped, the normal start screen returns. */
  function handleForgetPairingCode() {
    forgetPendingPairingCode()
    setPendingPairingCode(undefined)
    setError('')
  }

  /** Local only: forgets the remembered channelSessionId and resets all channel state. No backend call. */
  function handleClearChannel() {
    forgetChannelSessionId()
    setRememberedChannelSessionId(null)
    clearChannelState()
    setError('')
    logEvent('Kanal lokal geleert (kein Backend-Aufruf)')
  }

  /** Forgets this device's identity: deletes the DPoP key and generates a new one. Creates no channel. */
  async function handleRecreateKey() {
    try {
      setError('')
      forgetChannelSessionId()
      setRememberedChannelSessionId(null)
      clearChannelState()
      setDeviceLink(null)
      await resetDpopKeyPair()
      const keyPair = await getOrCreateDpopKeyPair()
      setDpop(keyPair)
      const thumbprint = await computeJwkThumbprint(keyPair.publicJwk)
      setJwkThumbprint(thumbprint)
      logEvent('DPoP-Key neu erzeugt', { response: { jwkThumbprint: thumbprint, publicJwk: keyPair.publicJwk } })
    } catch (err) {
      setError(describeError(t('Key-Neuerzeugung fehlgeschlagen'), err))
    }
  }

  /** Keeps the DPoP key but ends this session on the backend. The user picks the next start. */
  async function handleLogout() {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await startLogout(dpop, channelSessionId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Abmelden fehlgeschlagen'), err))
    }
  }

  /**
   * Raises this channel's required level. If the evidence does not satisfy it, the channel moves to
   * STEP_UP_IN_PROGRESS and `next` points at an AUTH tool, rendered as in LOGIN. A 410 (level
   * unreachable with the enrolled methods) surfaces via the normal error path.
   */
  async function handleStepUp(requiredAcr: string) {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await raiseRequiredAcr(dpop, channelSessionId, requiredAcr)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Step-up fehlgeschlagen'), err))
    }
  }

  /**
   * Answers whatever AnswerableState/Prompt the current step is waiting on instead of a tool run
   * (device-binding offer, account-deletion confirmation, ...). Both answers continue the journey -
   * declining is a valid outcome, not a cancel.
   */
  async function handleAnswer(accept: boolean) {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      // A declined question on the way (e.g. the re-identification) ends the wish.
      if (!accept) pendingOutcomeNoticeRef.current = undefined
      const response = await answerPrompt(dpop, channelSessionId, accept)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Antwort fehlgeschlagen'), err))
    }
  }

  /** Voluntary enrollment on an AUTHENTICATED channel; the auto-activate effect picks up the tool. */
  async function handleAddMethod() {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await startManageMethods(dpop, channelSessionId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Hinzufügen fehlgeschlagen'), err))
    }
  }

  /**
   * Confirms a WEB-channel QR login from this authenticated channel. Gates on loa2 (step-up first
   * if needed), then approve-qr, like the cold-entry 'confirmPeerLogin' start.
   */
  async function handlePeerLogin() {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await startPeerLogin(dpop, channelSessionId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Web-Login-Bestätigung fehlgeschlagen'), err))
    }
  }

  async function handleDeactivateMethod(methodInstanceId: string) {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await deactivateMethod(dpop, channelSessionId, methodInstanceId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Deaktivieren fehlgeschlagen'), err))
    }
  }

  /** Starts changing a method in place; step-up, re-confirmation and the enrollment follow via next/stepData. */
  async function handleChangeMethod(methodInstanceId: string) {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await changeMethod(dpop, channelSessionId, methodInstanceId)
      pendingOutcomeNoticeRef.current = t('Anmeldeverfahren geändert.')
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Ändern fehlgeschlagen'), err))
    }
  }

  /** Starts the account-deletion journey; confirmation and re-authentication follow via next/stepData. */
  async function handleDeleteAccount() {
    if (!dpop || !channelSessionId) return
    try {
      setError('')
      const response = await startAccountDeletion(dpop, channelSessionId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Konto löschen fehlgeschlagen'), err))
    }
  }

  /**
   * Declines the running tool - the way out a tool offers itself (ToolRenderContext.onSkip, e.g.
   * ident-kvnr's "jetzt nicht"). On a fallback state the chain moves on; on a mandatory one the full
   * choice comes back. The sticky bar's "Zurück" is [handleBack] instead: back to the selection
   * without declining anything. Abbrechen ends the whole journey.
   */
  async function handleAbandonTool() {
    if (!dpop || !activeTool) return
    try {
      setError('')
      pendingOutcomeNoticeRef.current = undefined
      const response = await abandonTool(dpop, activeTool.toolSessionId, activeTool.toolId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Wechsel fehlgeschlagen'), err))
    }
  }

  /** "Zurück": back to the selection, the running tool still on it - nothing is declined. */
  async function handleBack() {
    if (!dpop || !activeTool) return
    try {
      setError('')
      const response = await backFromTool(dpop, activeTool.toolSessionId, activeTool.toolId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Zurück fehlgeschlagen'), err))
    }
  }

  /** Nothing proven yet, so nothing to lose: end the journey and go back to the start screen. */
  async function handleLeaveToStart() {
    await handleCancel()
    handleClearChannel()
  }

  async function handleCancel() {
    if (!dpop || !channelSessionId) return
    try {
      pendingOutcomeNoticeRef.current = undefined
      const response = await cancelJourney(dpop, channelSessionId)
      applyResponse(response)
    } catch (err) {
      setError(describeError(t('Abbrechen fehlgeschlagen'), err))
    }
  }

  /**
   * approve-qr is the only tool whose activation body carries anything (a one-off, not a
   * generic dispatch). A known pairing code goes straight into the activation POST, so the backend
   * skips the `input` step (ConfirmQrLoginToolController).
   */
  function activationBodyFor(toolId: string): Record<string, unknown> | undefined {
    if (toolId !== 'approve-qr') return undefined
    const pairingCode = loadPendingPairingCode()
    if (!pairingCode) return undefined
    forgetPendingPairingCode()
    setPendingPairingCode(undefined)
    return { pairingCode }
  }

  async function handleSelectMethod(toolId: string) {
    if (!dpop || !channelSessionId) return
    try {
      // The options just shown minus the one being picked = how many real alternatives remain.
      setAlternativesCount(Math.max(0, (stepDataOf(stepData, 'select-method')?.options.length ?? 1) - 1))
      const response = await activateTool(dpop, channelSessionId, toolId, activationBodyFor(toolId))
      applyResponse(response, toolId)
    } catch (err) {
      setError(describeError(t('Verfahren konnte nicht gestartet werden'), err))
    }
  }

  const uiComponent = getUIComponent(next)
  // Nothing to cancel before a process even started, or once it's already finished.
  const canCancel = !!next && !(next.type === 'orchestrator' && next.context === 'authentication' && next.step === 'authenticated')
  // While a tool awaits input or the user picks one (select-method), other actions only compete
  // with Abbrechen.
  const inToolMode = !!activeTool || uiComponent === 'select-method'
  // What the running journey chain is for right now (its innermost entry), shown as a context
  // banner past the start screen. Undefined when nothing runs; then there is no banner.
  const journeyContextKey = currentJourneyDiagramKey(demo?.journeys)
  // Nur ein Konto im Aufbau geht mit einem Abbruch verloren, samt Identität und Verfahren
  // (ADR-46). Das Backend zeigt es als REGISTERING, auch ohne Demomodus. Eine Anmeldung oder ein
  // Step-Up auf einem bestehenden Konto lässt nichts zurück, dort bleibt es beim "Abbrechen".
  const discardsRegistration = channelState === 'REGISTERING'
  // Nicht angemeldet: Jeder Ausweg im Telefon endet auf der Startseite, auf jedem Bildschirm.
  // Ein Abbruch allein ließe das Backend nur dieselbe Einstiegs-Journey neu beginnen, und wer
  // schon etwas nachgewiesen hat, käme so nie mehr heraus. Neu beginnen bietet die Demo-Spalte
  // ("Journey neu starten"). Angemeldet führt "Abbrechen" zur Übersicht, von dort "Abmelden".
  // Wie ChannelState.isLoggedIn im Backend: Ein Step-Up läuft auf einer bestehenden Anmeldung.
  const loggedIn = channelState === 'AUTHENTICATED' || channelState === 'STEP_UP_IN_PROGRESS'
  const leavesToStart = canCancel && !loggedIn
  // Same box the innermost journey's hint highlights in JourneyStructureView (stateType).
  const journeyContextCurrentStep = journeyContextKey ? CURRENT_STEP_BY_STATE_TYPE[journeyContextKey]?.[demo?.journeys?.at(-1)?.stateType ?? ''] : undefined

  // Everything a tool's render() needs (src/tools/registry.ts). Each tool module calls its own
  // api.ts and reports back via onResult/onError.
  const selection = stepDataOf(stepData, 'select-method')
  const enrollmentRows = enrollmentChoiceRows(enrollmentOrder, selection?.options ?? [], setUpMethods)
  const confirmPrompt = confirmPromptOf(stepDataOf(stepData, 'confirm')?.prompt)
  const stepMessage = stepDataOf(stepData, 'message')?.message
  const message = stepMessage ? resolveText(stepMessage) : (carriedMessage ?? outcomeNotice)

  const toolCtx: ToolRenderContext | undefined =
    dpop && next?.type === 'tool' && next.toolId
      ? {
          step: next.step,
          toolId: next.toolId,
          toolSessionId: next.toolSessionId ?? (activeTool?.toolId === next.toolId ? activeTool.toolSessionId : undefined),
          proof: { kind: 'dpop', dpop },
          stepData,
          message,
          demo,
          onResult: (response) => applyResponse(response, next.toolId),
          onError: (message) => setError(message),
          onSkip: activeTool ? handleAbandonTool : undefined,
        }
      : undefined

  // The demo column's "why / what / who" for whatever the phone shows right now (StepExplanation):
  // a tool step explains itself (ToolModule.explain), the orchestrator's own screens are explained here.
  const stepExplanation = ((): { idleReason?: string; does: string; actor: string; details?: ReactNode; technical?: string } | undefined => {
    if (toolCtx) {
      const explained = explainToolStep(toolCtx.toolId, toolCtx.step)
      return explained && { ...explained, technical: `Tool ${toolCtx.toolId} · ${toolCtx.step}` }
    }
    const technical = next?.type === 'orchestrator' ? `Orchestrator ${next.context} · ${next.step}` : undefined
    if (!channelSessionId) {
      if (pendingPairingCode) {
        return {
          idleReason: t('Die App wurde über den QR-Code eines Browsers geöffnet.'),
          does: t('Bestätigen eröffnet eine Sitzung beim Orchestrator, um die wartende Anmeldung im Browser freizugeben.'),
          actor: t('Sie. Noch läuft keine Sitzung.'),
        }
      }
      return deviceLink?.linked
        ? {
            idleReason: t('Dieses Gerät ist mit einem Konto verbunden - deshalb bietet die App gleich das Anmelden an.'),
            does: t('Anmelden eröffnet eine Sitzung beim Orchestrator, der das Verfahren dieses Geräts vorschlägt.'),
            actor: t('Sie. Noch läuft keine Sitzung.'),
          }
        : {
            idleReason: t('Dieses Gerät gehört noch zu keinem Konto.'),
            does: t('Anmelden sucht ein bestehendes Konto, Registrieren legt ein neues an. Beides eröffnet eine Sitzung beim Orchestrator.'),
            actor: t('Sie. Noch läuft keine Sitzung.'),
          }
    }
    if (uiComponent === 'select-method') {
      return {
        does: t('Der Orchestrator zeigt die Verfahren, die dieser Schritt zulässt - nur solche, die diese App kann und die noch nicht abgelehnt wurden.'),
        actor: t('Sie wählen. Der Orchestrator wartet.'),
        // Only here does "not offered" explain something: why a method is missing from this choice.
        details: <UnavailableTools channel="APP" availableTools={availableTools} />,
        technical,
      }
    }
    if (uiComponent === 'prompt') {
      return {
        does: t('Eine Ja/Nein-Rückfrage des Orchestrators. Ihr Text kommt vom Backend, damit er sich ohne neue App-Version ändern lässt.'),
        actor: t('Sie antworten. Der Orchestrator wartet.'),
        technical,
      }
    }
    if (uiComponent === 'authentication-completed') {
      return {
        idleReason: t('Die Anmeldung ist abgeschlossen, gerade läuft kein Vorgang.'),
        does: t('Die App ruft Daten mit ihrem AccessToken ab. Das Token ist an den Schlüssel der App gebunden (DPoP) und nützt ohne ihn nichts.'),
        actor: t('Sie. Sicherheitsniveau erhöhen, Verfahren ändern oder Abmelden starten je einen neuen Vorgang.'),
        technical,
      }
    }
    return undefined
  })()

  // The frame's own way out of a step, in the quiet row on top of the phone's sheet.
  const stepNav = ((inToolMode && ((activeTool && (innerBack || alternativesCount > 0)) || canCancel)) || leavesToStart) && (
    <StepNav>
      {/* Ein Tool mit eigenem skipLabel zeichnet den Ausweg selbst, direkt neben
          seinem Absenden-Button (ToolRenderContext.onSkip); sonst stuende er zweimal auf dem
          Bildschirm. */}
      {activeTool && innerBack && (
        <button className="back" onClick={innerBack}>
          {t('Zurück')}
        </button>
      )}
      {activeTool && !innerBack && alternativesCount > 0 && !metaFor(activeTool.toolId).skipLabel && (
        <button className="back" onClick={handleBack} title={t('Zurück zur Auswahl. Dieses Verfahren bleibt dort wählbar.')}>
          {t('Zurück')}
        </button>
      )}
      {/* Nicht an channelState gekoppelt: Auch ein STEP_UP_IN_PROGRESS-Kanal (z.B. in
          CONFIRM_PEER_LOGIN) braucht den Ausweg. canCancel allein genügt, JourneyService
          kennt den Zielzustand nach dem Abbruch. */}
      {/* In einer laufenden Registrierung ist das kein "Abbrechen" im Sinne von
          "diesen Schritt lassen": Die Journey endet, und was sie bis dahin aufgebaut
          hat - bis hin zur nachgewiesenen Identität - wird weggeworfen. Also heißt der
          Knopf, was er tut, und fragt einmal nach. */}
      {canCancel && !leavesToStart && (
        <button onClick={handleCancel} title={t('Bricht diesen Vorgang vollständig ab.')}>
          {t('Abbrechen')}
        </button>
      )}
      {/* Only a registration builds something a cancel throws away - there it asks first
          (below). Anything else leaves straight away; before anything is proven, and with no
          tool running, it is simply the way back. */}
      {leavesToStart && !(discardsRegistration && hasProvenFactor) && (
        <button
          className={activeTool || hasProvenFactor ? undefined : 'back'}
          onClick={handleLeaveToStart}
          title={t('Zurück zur Startseite. Bisher ist nichts gespeichert.')}
        >
          {activeTool || hasProvenFactor ? t('Abbrechen') : t('Zurück')}
        </button>
      )}
      {canCancel && discardsRegistration && hasProvenFactor && (
        <button
          onClick={() => setConfirmingDiscard(true)}
          disabled={confirmingDiscard}
          title={t('Beendet die Registrierung. Alles, was dieser Vorgang bisher aufgebaut hat - auch eine bereits nachgewiesene Identität - wird verworfen.')}
        >
          {t('Registrierung verwerfen')}
        </button>
      )}
    </StepNav>
  )

  // Die Rückfrage zum Verwerfen nimmt den Platz der Aktionen des Schritts ein: Solange sie offen
  // ist, gibt es nur diese eine Entscheidung.
  const discardQuestion = leavesToStart && discardsRegistration && hasProvenFactor && confirmingDiscard && (
    <div className="phone__bar-question">
      <p>{t('Alles aus diesem Vorgang geht verloren, auch die nachgewiesene Identität.')}</p>
      <div className="form-actions">
        <button onClick={() => setConfirmingDiscard(false)}>{t('Weitermachen')}</button>
        <button className="secondary destructive-text" onClick={() => { setConfirmingDiscard(false); handleLeaveToStart() }}>
          {t('Verwerfen')}
        </button>
      </div>
    </div>
  )

  return (
    <DemoProvider>
      {(demoTargets) => (
        <div className="app-frame channel-app">
          <ChannelNav area="app" />
          <div className="app-stage">
            <div className="app-stage__phone">
              <PhoneFrame title="Demo" footer={discardQuestion || undefined}>
                {stepNav}
                {stepExplanation && (
                  <Demo>
                    <StepExplanation
                      journeys={demo?.journeys}
                      {...stepExplanation}
                      journeyTitle={journeyContextKey ? journeyContextLabel(journeyContextKey) : undefined}
                      journeyDiagram={
                        journeyContextKey && (
                          <DiagramTrigger
                            spec={JOURNEY_DIAGRAMS[journeyContextKey]}
                            current={journeyContextCurrentStep}
                            label={t('Ablauf dieses Vorgangs als Diagramm anzeigen')}
                            openDown
                          />
                        )
                      }
                    />
                  </Demo>
                )}
                {error && (
                  <div className="card error-card">
                    <h2>{t('Fehler')}</h2>
                    <p>{error}</p>
                  </div>
                )}

                {!channelSessionId && (
                  // What the app itself would show in its current state (docs/10-frontend.md): the
                  // way in it offers this device. Every other way in is in the demo column.
                  <div className="card app-home">
                    {pendingPairingCode ? (
                      <>
                        <h2>{t('Web-Login bestätigen')}</h2>
                        <p>{t('Sie haben einen QR-Code gescannt. Bestätigen Sie die Anmeldung im Browser mit dieser App.')}</p>
                        <CodeDisplay code={pendingPairingCode} label={t('Pairing-Code')} />
                        {deviceLink?.linked === false ? (
                          // Confirming needs an account on this device - nothing to offer here but the
                          // way back; signing in is the ordinary start screen's job.
                          <>
                            <p>{t('Diese App ist noch mit keinem Konto verbunden. Melden Sie sich zuerst an und scannen Sie den QR-Code danach erneut.')}</p>
                            <div className="form-actions">
                              <button className="secondary" onClick={handleForgetPairingCode}>
                                {t('Abbrechen')}
                              </button>
                            </div>
                          </>
                        ) : (
                          <>
                            <div className="form-actions app-home__actions">
                              <button onClick={() => handleStart('confirmPeerLogin')}>{t('Anmeldung bestätigen')}</button>
                            </div>
                            <button className="link-button" onClick={handleForgetPairingCode}>
                              {t('Abbrechen')}
                            </button>
                            <ButtonDiagrams entries={[{ label: t('Anmeldung bestätigen'), diagram: 'confirmPeerLogin' }]} />
                          </>
                        )}
                      </>
                    ) : deviceLink?.linked ? (
                      <>
                        <h2>{t('Willkommen zurück')}</h2>
                        <p>
                          {t('Dieses Gerät ist mit Ihrem Konto verbunden.')}
                        </p>
                        {/* The device's own way in first; the two without it (a lookup login, a
                            fresh registration) stay reachable - same wording as on an unlinked device.
                            Each label says what it does: whose account, by which means, or a new one. */}
                        <div className="form-actions app-home__actions">
                          <button onClick={() => handleStart('auto')}>
                            {t('Mit diesem Gerät anmelden')}
                          </button>
                          <button className="secondary" onClick={() => handleStart('login')}>
                            {t('Mit E-Mail-Adresse anmelden')}
                          </button>
                          <button className="secondary" onClick={() => handleStart('register')}>
                            {t('Anderes Konto benutzen')}
                          </button>
                        </div>
                        <ButtonDiagrams
                          entries={[
                            {
                              label: t('Mit diesem Gerät anmelden'),
                              diagram: 'auto',
                            },
                            { label: t('Mit E-Mail-Adresse anmelden'), diagram: 'login' },
                            { label: t('Anderes Konto benutzen'), diagram: 'register' },
                          ]}
                        />
                        {/* Like reinstalling the app: the device key goes, so the orchestrator no
                            longer recognizes this device - the account itself stays untouched. */}
                        {confirmingReset ? (
                          <div className="app-home__reset">
                            <p className="hint">
                              {t('Danach erkennt die App Ihr Konto nicht mehr. Anmelden können Sie sich weiter mit Ihrer E-Mail-Adresse.')}
                            </p>
                            <div className="form-actions app-home__actions">
                              <button className="destructive" onClick={() => { setConfirmingReset(false); handleRecreateKey() }}>
                                {t('Zurücksetzen')}
                              </button>
                              <button className="secondary" onClick={() => setConfirmingReset(false)}>
                                {t('Abbrechen')}
                              </button>
                            </div>
                          </div>
                        ) : (
                          <button className="link-button" onClick={() => setConfirmingReset(true)}>
                            {t('Dieses Gerät zurücksetzen')}
                          </button>
                        )}
                      </>
                    ) : (
                      <>
                        <h2>{t('Willkommen')}</h2>
                        <p>{t('Melden Sie sich mit Ihrem Konto an, oder legen Sie ein neues an.')}</p>
                        <div className="form-actions app-home__actions">
                          <button onClick={() => handleStart('login')}>{t('Mit E-Mail-Adresse anmelden')}</button>
                          <button className="secondary" onClick={() => handleStart('register')}>
                            {t('Neues Konto anlegen')}
                          </button>
                        </div>
                        <ButtonDiagrams
                          entries={[
                            { label: t('Mit E-Mail-Adresse anmelden'), diagram: 'login' },
                            { label: t('Neues Konto anlegen'), diagram: 'register' },
                          ]}
                        />
                      </>
                    )}
                  </div>
                )}

                {uiComponent === 'select-method' && selection && (
                  <SelectMethodView
                    options={enrollmentChoice ? enrollmentRows.rows : selection.options}
                    setUp={enrollmentChoice ? enrollmentRows.setUp : undefined}
                    title={selection.title ? resolveText(selection.title) : t('Verfahren wählen')}
                    description={selection.description ? resolveText(selection.description) : undefined}
                    onSelect={handleSelectMethod}
                  />
                )}

                {/* Only once the tool runs: a form shown while its activation is in flight takes input it cannot send. */}
                {toolCtx?.toolSessionId && <InnerBackProvider value={innerBackRegistry}>{renderToolStep(toolCtx)}</InnerBackProvider>}

                {uiComponent === 'prompt' && confirmPrompt && (
                  <PromptView prompt={confirmPrompt} onAnswer={handleAnswer} />
                )}

                {uiComponent === 'authentication-completed' && dpop && channelSessionId && (
                  <AuthenticationCompletedView
                    dpop={dpop}
                    channelSessionId={channelSessionId}
                    currentAcr={currentAcr}
                    currentAmr={currentAmr}
                    activeMethods={activeMethods}
                    demo={demo}
                    view={accountView}
                    onNavigate={setAccountView}
                    onAddMethod={handleAddMethod}
                    onDeactivateMethod={handleDeactivateMethod}
                    onChangeMethod={handleChangeMethod}
                    onDeleteAccount={handleDeleteAccount}
                    onStepUp={handleStepUp}
                    onPeerLogin={handlePeerLogin}
                    onLogout={handleLogout}
                    infoMessage={message}
                  />
                )}

              </PhoneFrame>
            </div>

            <DemoArea
              head={{ title: t('App-Kanal'), tag: t('Echt · Orchestrator') }}
              targets={demoTargets}
              session={
                <SessionSummary
                  signedIn={channelState === 'AUTHENTICATED'}
                  name={demo?.session?.personName}
                  acr={demo?.session?.acr ?? currentAcr}
                  amr={demo?.session?.amr ?? currentAmr}
                />
              }
              actions={
                // Demo-only ways to act; the ways in a real app offers are the phone's own buttons.
                channelSessionId ? (
                  <>
                    {canCancel && (
                      <DemoAction
                        text={t('Startet diese Journey von vorne, mit demselben Ziel (z. B. erneut identifizieren).')}
                        label={t('Journey neu starten')}
                        onClick={handleCancel}
                      />
                    )}
                    <DemoAction
                      text={t('Vergisst diese Sitzungs-ID lokal (kein Backend-Aufruf) - der nächste Schritt eröffnet einen neuen Channel.')}
                      label={t('Sitzung vergessen')}
                      onClick={handleClearChannel}
                    />
                  </>
                ) : (
                  <>
                    {rememberedChannelSessionId && (
                      <DemoAction
                        text={t('Dort weitermachen, wo Sie aufgehört haben ({sitzung})', { sitzung: shorten(rememberedChannelSessionId) })}
                        label={t('Sitzung fortsetzen')}
                        ariaLabel={t('Sitzung fortsetzen ({sitzung})', { sitzung: shorten(rememberedChannelSessionId) })}
                        onClick={() => handleStart('resume')}
                      />
                    )}
                    <DemoAction
                      text={t(
                        'Löscht diesen DPoP-Schlüssel und erzeugt einen neuen - das Gerät gilt danach als unbekannt, jede laufende Sitzung wird lokal verworfen.',
                      )}
                      label={t('Geräte-Kennung neu erzeugen')}
                      onClick={handleRecreateKey}
                    />
                  </>
                )
              }
              intro={{
                id: 'app',
                title: t('Dieser Tab ist Ihr Smartphone'),
                body: (
                  <>
                    <p>
                      <Tx
                        text={
                          'Stellen Sie sich vor, Sie öffnen die App Ihrer Versicherung. Der Tab spielt diese App: Beim ersten ' +
                          'Aufruf hat er einen {schluessel} erzeugt, der den Browser nie verlässt, und ' +
                          'signiert damit jede Anfrage (DPoP). Ein abgefangenes Token nützt so auf keinem anderen Gerät.'
                        }
                        schluessel={<strong>{t('DPoP-Schlüssel')}</strong>}
                      />
                    </p>
                    <p>
                      <Tx
                        text={
                          'Beim ersten Mal {registrieren} Sie sich: ausweisen (Freischaltcode aus dem Brief, eID oder Nect), ' +
                          'E-Mail-Adresse bestätigen und ein Anmeldeverfahren einrichten. Danach {anmelden} - auf diesem Gerät ' +
                          'auch automatisch. Echt ist dabei der Orchestrator mit allen Regeln; simuliert sind Handy, SMS und ' +
                          'E-Mail (der Code steht im Formular), Brief, Ausweiskarte, Nect, KOBIL und das Personenverzeichnis.'
                        }
                        registrieren={<strong>{t('registrieren')}</strong>}
                        anmelden={<strong>{t('melden Sie sich an')}</strong>}
                      />
                    </p>
                  </>
                ),
              }}
              background={
                <>
                  <DeviceIdentityCard jwkThumbprint={jwkThumbprint} deviceLink={deviceLink} />
                  <Disclosure summary={t('Einstellungen für den nächsten Start: Sicherheitsniveau und Verfahren dieser App')}>
                    <p>{t('Wirkt erst auf den nächsten neu gestarteten Vorgang, nicht rückwirkend auf einen laufenden.')}</p>
                    <UnavailableTools channel="APP" availableTools={availableTools} />
                    <label className="field-row">
                      {t('Startniveau:')}
                      <select value={requiredAcr} onChange={(e) => setRequiredAcr(e.target.value)}>
                        <option value="">{t('loa1 (Standard)')}</option>
                        <option value="loa2">{t('loa2 (MFA - mehrere Enrollments)')}</option>
                      </select>
                    </label>
                    <ToolAvailabilitySelector availableTools={availableTools} onChange={setAvailableTools} />
                  </Disclosure>
                  <JourneyStructureView channelSessionId={channelSessionId} channelState={channelState} journeys={demo?.journeys} next={next} />
                  <DebugSidebar
                    channel={{ channelSessionId, channelState, currentAcr, currentAmr, activeMethods, next, stepData, demo, activeTool }}
                    log={debugLog}
                  />
                </>
              }
            />
          </div>
        </div>
      )}
    </DemoProvider>
  )
}
