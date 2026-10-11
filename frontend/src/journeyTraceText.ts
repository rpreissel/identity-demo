import { CURRENT_STEP_BY_STATE_TYPE, INTENT_DIAGRAM_KEY, JOURNEY_DIAGRAMS, diagramKeyForState } from './journeyDiagrams'
import { language, resolveText, t, type TextRef } from './texts'
import { catalogEntry } from './toolCatalog'
import { metaFor } from './tools/registry'
import type { JourneyTraceEntryView, JourneyTraceOutcome } from './types'

/**
 * Reads the journey trace for people (admin page, JourneyTraceView): a title per step, the
 * decision it led to, and the detail keys JourneyTraceDetails logs as labelled tags with an
 * explanation. The raw names stay visible behind the view's "technical names" switch.
 */

export const dateTimeFormat = (iso: string) => new Date(iso).toLocaleString(language(), { dateStyle: 'medium', timeStyle: 'medium' })

export function durationLabel(fromIso: string, toIso: string): string {
  const seconds = Math.round((Date.parse(toIso) - Date.parse(fromIso)) / 1000)
  if (seconds < 60) return t('{sekunden} s', { sekunden: seconds })
  return t('{minuten} min {sekunden} s', { minuten: Math.floor(seconds / 60), sekunden: seconds % 60 })
}

export function channelTypeLabel(channelType?: string): string {
  if (channelType === 'APP') return t('App')
  if (channelType === 'WEB') return t('Website')
  return t('Unbekannt')
}

/** What kind of step an entry is - the view filters and colours by it. */
export type StepCategory = 'lifecycle' | 'tool' | 'failure' | 'subJourney' | 'action'

export const STEP_CATEGORIES: StepCategory[] = ['lifecycle', 'tool', 'failure', 'subJourney', 'action']

export function categoryLabel(category: StepCategory): string {
  switch (category) {
    case 'lifecycle':
      return t('Start und Ende')
    case 'tool':
      return t('Verfahren')
    case 'failure':
      return t('Fehlversuche')
    case 'subJourney':
      return t('Sub-Journeys')
    case 'action':
      return t('Aktionen und Antworten')
  }
}

export function stepCategory(entry: JourneyTraceEntryView): StepCategory {
  const decision = entry.detail.decision
  if (entry.eventType === 'TOOL_FAILED' || decision === 'Abort') return 'failure'
  if (decision === 'RequireSubJourney' || entry.eventType === 'SubJourneyFinished' || entry.eventType === 'SubJourneyCancelled') {
    return 'subJourney'
  }
  if (['TOOL_ACTIVATED', 'Completed', 'Abandoned', 'Back'].includes(entry.eventType)) return 'tool'
  if (entry.eventType === 'Answered' || entry.eventType === 'ActionCompleted' || entry.eventType === 'Entry' || decision === 'Perform') {
    return 'action'
  }
  return 'lifecycle'
}

/** The tool an entry names, as `toolId` or in its declared version (`enroll-sms@1`, ADR-51). */
export function toolIdOf(detail: Record<string, unknown>): string | undefined {
  if (typeof detail.toolId === 'string') return detail.toolId
  if (typeof detail.tool === 'string') return detail.tool.split('@')[0]
  return undefined
}

/** The tool's name from the catalog; its id where the catalog does not know it. */
export function toolLabel(toolId: string): string {
  return catalogEntry(toolId) ? metaFor(toolId).label : toolId
}

/** The intent's name as the journey diagrams call it. */
export function intentLabel(intent: string | undefined): string {
  if (!intent) return t('Gerätesitzung')
  const key = INTENT_DIAGRAM_KEY[intent]
  if (key) return JOURNEY_DIAGRAMS[key].title
  if (intent === 'WEB_SELECT_METHOD') return t('Anmeldung im Browser')
  if (intent === 'LOGOUT') return t('Abmelden')
  return intent
}

/** The diagram box a journey state stands for; the state's own name where no box is drawn for it. */
export function stateLabel(intent: string | undefined, state: string | undefined): string {
  if (!state) return '–'
  const key = intent ? diagramKeyForState(intent, state) : undefined
  const step = key ? CURRENT_STEP_BY_STATE_TYPE[key]?.[state] : undefined
  if (!key || !step) return state
  const spec = JOURNEY_DIAGRAMS[key]
  return (step.branch ? spec.branch?.steps : spec.steps)?.[step.index] ?? state
}

export function outcomeLabel(outcome: JourneyTraceOutcome): string {
  switch (outcome) {
    case 'SUCCEEDED':
      return t('Erfolgreich')
    case 'FAILED':
      return t('Gescheitert')
    case 'CANCELLED':
      return t('Abgebrochen')
    case 'LOGGED_OUT':
      return t('Abgemeldet')
    case 'OPEN':
      return t('Ohne Abschluss')
  }
}

/** `loa2` as people read it. */
export function acrLabel(value: unknown): string {
  if (typeof value !== 'string') return '–'
  if (value === 'none') return t('kein Niveau')
  return value.startsWith('loa') ? `LoA ${value.slice(3)}` : value
}

function textOf(value: unknown): string {
  if (value && typeof value === 'object' && 'key' in value) return resolveText(value as TextRef)
  return String(value)
}

/** One line per step: what happened, in words. */
export function stepTitle(entry: JourneyTraceEntryView): string {
  const toolId = toolIdOf(entry.detail)
  const tool = toolId ? toolLabel(toolId) : ''
  const subIntent = intentLabel(entry.detail.subIntent as string | undefined)
  switch (entry.eventType) {
    case 'Started':
      return t('Journey gestartet')
    case 'Entry':
      return t('Einstieg mit übernommenem Nachweis')
    case 'TOOL_ACTIVATED':
      return t('{verfahren} gestartet', { verfahren: tool })
    case 'Completed':
      return t('{verfahren} erfolgreich', { verfahren: tool })
    case 'TOOL_FAILED':
      return t('{verfahren} fehlgeschlagen', { verfahren: tool })
    case 'Abandoned':
      return t('{verfahren} abgelehnt, anderes Verfahren gewünscht', { verfahren: tool })
    case 'Back':
      return t('Zurück aus {verfahren}', { verfahren: tool })
    case 'Answered':
      return t('Antwort: {antwort}', { antwort: String(entry.detail.answer ?? '–') })
    case 'SubJourneyFinished':
      return t('Sub-Journey „{anliegen}“ erfolgreich, zurück in dieser Journey', { anliegen: subIntent })
    case 'SubJourneyCancelled':
      return t('Sub-Journey „{anliegen}“ abgebrochen, zurück in dieser Journey', { anliegen: subIntent })
    case 'ActionCompleted':
      return t('Aktion ausgeführt')
    case 'CANCELLED':
      return t('Journey abgebrochen')
    case 'LOGGED_OUT':
      return t('Abgemeldet')
    case 'EXPIRED':
      return t('Gerätesitzung abgelaufen')
    default:
      return entry.eventType
  }
}

export type DecisionTone = 'neutral' | 'success' | 'subJourney' | 'failure'

/** Where the step led (JourneyTraceDetails.transitionDetail) - absent for entries without a decision. */
export function decisionOf(entry: JourneyTraceEntryView): { text: string; tone: DecisionTone } | undefined {
  const detail = entry.detail
  switch (detail.decision) {
    case 'To':
      return { text: t('weiter zu „{schritt}“', { schritt: stateLabel(entry.intent, detail.toState as string | undefined) }), tone: 'neutral' }
    case 'Authenticated':
      return { text: t('Ziel erreicht, die Journey ist erfolgreich beendet'), tone: 'success' }
    case 'RequireSubJourney':
      return {
        text: t('braucht zuerst „{anliegen}“ bis {niveau}, die Sub-Journey folgt direkt darunter', {
          anliegen: intentLabel(detail.subIntent as string | undefined),
          niveau: acrLabel(detail.targetAcr),
        }),
        tone: 'subJourney',
      }
    case 'Perform':
      return { text: t('führt aus: {aktion}', { aktion: String(detail.action ?? '–') }), tone: 'neutral' }
    case 'Logout':
      return { text: t('Abmeldung'), tone: 'neutral' }
    case 'Cancel':
      return { text: t('die Journey wird abgebrochen'), tone: 'failure' }
    case 'Abort':
      return { text: t('die Journey scheitert: {grund}', { grund: textOf(detail.reason ?? '–') }), tone: 'failure' }
    default:
      return undefined
  }
}

export interface DetailTag {
  key: string
  label: string
  value: string
  explanation: string
}

/** Keys the title or the decision already says. */
const SHOWN_ELSEWHERE = new Set(['decision', 'toState', 'subIntent', 'toolId', 'answer', 'action'])

/** Most telling first; unknown keys follow in logged order. */
const ORDER = [
  'reason', 'attemptBudgetLeft', 'attemptedAccountId', 'attemptedPersonId', 'achievedAcr', 'resolvedAcr', 'acrFloor', 'targetAcr',
  'amr', 'factorTypes', 'accountId', 'personId', 'authCandidates', 'next', 'outcome', 'method', 'label', 'methodInstanceId',
  'attested', 'attribute', 'enrollmentRef', 'invitation', 'methods', 'accountAlreadyKnown', 'tool',
]

function tagText(key: string): { label: string; explanation: string } {
  switch (key) {
    case 'achievedAcr':
      return { label: t('Niveau dieses Verfahrens'), explanation: t('Was dieses eine Verfahren allein erreicht (LoA: Level of Assurance).') }
    case 'resolvedAcr':
      return { label: t('Gesamtniveau'), explanation: t('Das Niveau aus allen Nachweisen der Journey zusammen. Zwei schwächere Faktoren können zusammen ein höheres ergeben.') }
    case 'acrFloor':
      return { label: t('Mindestniveau'), explanation: t('Das Niveau, gegen das dieser Schritt geprüft wurde.') }
    case 'targetAcr':
      return { label: t('Zielniveau'), explanation: t('Das Niveau, das die Sub-Journey erreichen muss, bevor es hier weitergeht.') }
    case 'amr':
      return { label: t('Nachweis (AMR)'), explanation: t('Authentication Method Reference: welche Verfahren den Nachweis geliefert haben.') }
    case 'factorTypes':
      return { label: t('Faktoren'), explanation: t('Die Art des Nachweises: Wissen, Besitz oder Biometrie.') }
    case 'authCandidates':
      return { label: t('Angeboten'), explanation: t('Die Verfahren, die in diesem Zustand wählbar sind.') }
    case 'next':
      return { label: t('Client geht zu'), explanation: t('Wohin App oder Website als Nächstes navigieren.') }
    case 'reason':
      return { label: t('Grund'), explanation: t('Warum das Verfahren fehlgeschlagen ist.') }
    case 'attemptBudgetLeft':
      return { label: t('Versuche übrig'), explanation: t('Fehlversuche, die die ganze Journey noch erlaubt. Bei 0 endet sie.') }
    case 'attemptedAccountId':
      return { label: t('Versuchtes Konto'), explanation: t('Das Konto, auf das sich der Fehlversuch bezog.') }
    case 'attemptedPersonId':
      return { label: t('Versuchte Person'), explanation: t('Die Partnernummer, auf die sich der Fehlversuch bezog.') }
    case 'accountId':
      return { label: t('Konto'), explanation: t('Das Konto, das dieser Schritt aufgelöst hat.') }
    case 'personId':
      return { label: t('Person'), explanation: t('Die Partnernummer, die die Identifikation ergeben hat.') }
    case 'outcome':
      return { label: t('Ergebnis'), explanation: t('Wie das Verfahren erfolgreich abgeschlossen hat.') }
    case 'method':
      return { label: t('Methode'), explanation: t('Die Art des Verfahrens.') }
    case 'label':
      return { label: t('Bezeichnung'), explanation: t('Der Name der Methode, wie die Person sie sieht.') }
    case 'methodInstanceId':
      return { label: t('Methoden-ID'), explanation: t('Die Methode des Kontos, auf die die Aktion wirkt.') }
    case 'attested':
      return { label: t('Bestätigte Angaben'), explanation: t('Welche Angaben das Verfahren bestätigt hat, ohne ihre Werte.') }
    case 'attribute':
      return { label: t('Angabe'), explanation: t('Die Angabe, die zurückgezogen wird.') }
    case 'enrollmentRef':
      return { label: t('Einrichtung'), explanation: t('Verweis auf das eingerichtete Zugangsmittel im Modul des Verfahrens.') }
    case 'invitation':
      return { label: t('Einladung'), explanation: t('Die Einladung, über die angemeldet wurde.') }
    case 'methods':
      return { label: t('Übernommene Nachweise'), explanation: t('Nachweise, die aus einer früheren Sitzung übernommen wurden.') }
    case 'accountAlreadyKnown':
      return { label: t('Konto schon bekannt'), explanation: t('Ob das Konto vor der Wahl des Verfahrens schon feststand.') }
    case 'tool':
      return { label: t('Tool-Version'), explanation: t('Tool und Version, die der Client angegeben hat.') }
    default:
      return { label: key, explanation: '' }
  }
}

function factorLabel(factor: string): string {
  if (factor === 'KNOWLEDGE') return t('Wissen')
  if (factor === 'POSSESSION') return t('Besitz')
  if (factor === 'INHERENCE') return t('Biometrie')
  return factor
}

function formatValue(key: string, value: unknown): string {
  if (key.endsWith('Acr') || key === 'acrFloor') return acrLabel(value)
  if (key === 'reason') return textOf(value)
  if (key === 'factorTypes' && Array.isArray(value)) return value.map((f) => factorLabel(String(f))).join(' + ')
  if (key === 'authCandidates' && value && typeof value === 'object') return Object.keys(value).map(toolLabel).join(', ')
  if (key === 'next' && value && typeof value === 'object') {
    const next = value as Record<string, unknown>
    return next.type === 'tool' ? `${toolLabel(String(next.toolId))} · ${next.step}` : `${next.context} · ${next.step}`
  }
  if (typeof value === 'boolean') return value ? t('ja') : t('nein')
  if (Array.isArray(value)) return value.map((item) => (item && typeof item === 'object' ? describeObject(item) : String(item))).join(' | ')
  if (value && typeof value === 'object') return describeObject(value)
  return String(value)
}

function describeObject(value: object): string {
  return Object.entries(value)
    .filter(([, v]) => v !== null && v !== undefined && v !== '')
    .map(([k, v]) => `${tagText(k).label}: ${formatValue(k, v)}`)
    .join(', ')
}

/** The detail keys as labelled tags, most telling first, without what title and decision already say. */
export function detailTags(detail: Record<string, unknown>): DetailTag[] {
  const keys = Object.keys(detail).filter((key) => {
    const value = detail[key]
    return !SHOWN_ELSEWHERE.has(key) && value !== null && value !== undefined && value !== '' && !(Array.isArray(value) && value.length === 0)
  })
  const rank = (key: string) => (ORDER.includes(key) ? ORDER.indexOf(key) : ORDER.length)
  return keys
    .sort((a, b) => rank(a) - rank(b))
    .map((key) => ({ key, ...tagText(key), value: formatValue(key, detail[key]) }))
}
