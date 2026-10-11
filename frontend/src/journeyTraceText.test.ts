import { describe, expect, it } from 'vitest'
import { acrLabel, decisionOf, detailTags, stateLabel, stepCategory, stepTitle, toolLabel } from './journeyTraceText'
import type { JourneyTraceEntryView } from './types'

function entry(eventType: string, detail: Record<string, unknown> = {}, extra: Partial<JourneyTraceEntryView> = {}): JourneyTraceEntryView {
  return { channelSessionId: 'chan-1', journeyId: 'j-1', intent: 'STEP_UP', eventType, detail, createdAt: '2026-10-10T09:41:02Z', ...extra }
}

describe('journey trace texts', () => {
  it('names a step by what happened, with its tool', () => {
    expect(stepTitle(entry('TOOL_FAILED', { toolId: 'auth-sms', tool: 'auth-sms@1' }))).toBe(`${toolLabel('auth-sms')} fehlgeschlagen`)
    // Older entries carry only the declared version.
    expect(stepTitle(entry('TOOL_ACTIVATED', { tool: 'enroll-sms@1' }))).toBe(`${toolLabel('enroll-sms')} gestartet`)
    expect(stepTitle(entry('SubJourneyFinished', { subIntent: 'STEP_UP' }))).toContain('Sicherheitsniveau erhöhen')
  })

  it('says where the step led, and that a sub-journey follows', () => {
    expect(decisionOf(entry('Completed', { decision: 'Authenticated' }))).toEqual({ text: 'Ziel erreicht, die Journey ist erfolgreich beendet', tone: 'success' })
    const sub = decisionOf(entry('Answered', { decision: 'RequireSubJourney', subIntent: 'STEP_UP', targetAcr: 'loa3' }))
    expect(sub?.tone).toBe('subJourney')
    expect(sub?.text).toContain('LoA 3')
    expect(decisionOf(entry('TOOL_ACTIVATED', { toolId: 'auth-sms' }))).toBeUndefined()
  })

  it('reads the offered methods from authCandidates, the key the backend logs', () => {
    const tags = detailTags({ decision: 'To', toState: 'AuthChoice', authCandidates: { 'auth-sms': 'sms', 'auth-device': 'device' } })

    expect(tags).toEqual([expect.objectContaining({ key: 'authCandidates', label: 'Angeboten', value: `${toolLabel('auth-sms')}, ${toolLabel('auth-device')}` })])
  })

  it('labels every level with an explanation, most telling first', () => {
    const tags = detailTags({ resolvedAcr: 'loa3', achievedAcr: 'loa2', factorTypes: ['POSSESSION'], reason: { key: 'falscher-code-123456', template: 'Falscher Code' } })

    expect(tags.map((tag) => [tag.label, tag.value])).toEqual([
      ['Grund', 'Falscher Code'],
      ['Niveau dieses Verfahrens', 'LoA 2'],
      ['Gesamtniveau', 'LoA 3'],
      ['Faktoren', 'Besitz'],
    ])
    expect(tags.every((tag) => tag.explanation.length > 0)).toBe(true)
  })

  it('leaves out what title and decision already say, and empty values', () => {
    expect(detailTags({ decision: 'To', toState: 'Offer', toolId: 'auth-sms', amr: [], accountId: null })).toEqual([])
  })

  it('sorts steps into categories the view filters by', () => {
    expect(stepCategory(entry('TOOL_FAILED'))).toBe('failure')
    expect(stepCategory(entry('Answered', { decision: 'RequireSubJourney' }))).toBe('subJourney')
    expect(stepCategory(entry('Completed', { decision: 'Authenticated' }))).toBe('tool')
    expect(stepCategory(entry('ActionCompleted'))).toBe('action')
    expect(stepCategory(entry('CANCELLED'))).toBe('lifecycle')
  })

  it('names a state by its box in the journey diagram, else by itself', () => {
    expect(stateLabel('STEP_UP', 'AuthChoice')).not.toBe('AuthChoice')
    expect(stateLabel('STEP_UP', 'SomethingNew')).toBe('SomethingNew')
    expect(acrLabel('loa2')).toBe('LoA 2')
  })
})
