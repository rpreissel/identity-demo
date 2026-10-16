import { describe, expect, it } from 'vitest'
import { buildTimeline, type TimelineJourney, type TimelineItem } from './journeyTraceTimeline'
import type { JourneyTraceEntryView, JourneyTraceEvent } from './types'

function entry(
  createdAt: string,
  eventType: JourneyTraceEvent,
  journeyId?: string,
  extra: Partial<JourneyTraceEntryView> = {},
): JourneyTraceEntryView {
  return { channelSessionId: 'chan-1', channelType: 'APP', journeyId, intent: journeyId ? 'MANAGE_AUTH_METHODS' : undefined, eventType, detail: {}, createdAt, ...extra }
}

/** The parts of a journey as `step:<eventType>` / `sub:<journeyId>`, in order. */
function shape(journey: TimelineJourney): string[] {
  return journey.parts.map((part) => (part.kind === 'step' ? `step:${part.entry.eventType}` : `sub:${part.journey.journeyId}`))
}

function journeyAt(items: TimelineItem[], index: number): TimelineJourney {
  const item = items[index]
  if (item.kind !== 'journey') throw new Error(`item ${index} is no journey`)
  return item.journey
}

describe('buildTimeline', () => {
  it('places a sub-journey right after the step that required it, not at the end', () => {
    const entries = [
      entry('2026-10-10T09:41:02Z', 'STARTED', 'parent'),
      entry('2026-10-10T09:41:09Z', 'ANSWERED', 'parent', { detail: { decision: 'RequireSubJourney', subIntent: 'STEP_UP' } }),
      entry('2026-10-10T09:41:09.001Z', 'STARTED', 'sub', { parentJourneyId: 'parent', intent: 'STEP_UP' }),
      entry('2026-10-10T09:42:15Z', 'TOOL_COMPLETED', 'sub', { parentJourneyId: 'parent', intent: 'STEP_UP', detail: { decision: 'Authenticated' } }),
      entry('2026-10-10T09:42:15.001Z', 'SUB_JOURNEY_FINISHED', 'parent'),
      entry('2026-10-10T09:42:16Z', 'ACTION_COMPLETED', 'parent'),
    ]

    const items = buildTimeline(entries)

    expect(items).toHaveLength(1)
    expect(shape(journeyAt(items, 0))).toEqual(['step:STARTED', 'step:ANSWERED', 'sub:sub', 'step:SUB_JOURNEY_FINISHED', 'step:ACTION_COMPLETED'])
  })

  it('keeps a sub-journey logged in the same instant as its trigger behind that trigger', () => {
    const entries = [
      entry('2026-10-10T09:41:02Z', 'STARTED', 'parent'),
      entry('2026-10-10T09:41:09Z', 'ANSWERED', 'parent', { detail: { decision: 'RequireSubJourney' } }),
      entry('2026-10-10T09:41:09Z', 'STARTED', 'sub', { parentJourneyId: 'parent' }),
    ]

    expect(shape(journeyAt(buildTimeline(entries), 0))).toEqual(['step:STARTED', 'step:ANSWERED', 'sub:sub'])
  })

  it('without a RequireSubJourney step, places the sub-journey after the last step before it', () => {
    const entries = [
      entry('2026-10-10T09:41:02Z', 'STARTED', 'parent'),
      entry('2026-10-10T09:41:05Z', 'STARTED', 'sub', { parentJourneyId: 'parent' }),
      entry('2026-10-10T09:41:09Z', 'SUB_JOURNEY_CANCELLED', 'parent'),
    ]

    expect(shape(journeyAt(buildTimeline(entries), 0))).toEqual(['step:STARTED', 'sub:sub', 'step:SUB_JOURNEY_CANCELLED'])
  })

  it('orders journeys and journey-less channel events by time, and shows an orphaned sub-journey on its own', () => {
    const entries = [
      entry('2026-10-10T09:30:00Z', 'STARTED', 'first'),
      entry('2026-10-10T09:35:00Z', 'STARTED', 'orphan', { parentJourneyId: 'gone' }),
      entry('2026-10-10T09:52:00Z', 'CHANNEL_EXPIRED'),
    ]

    const items = buildTimeline(entries)

    expect(items.map((item) => (item.kind === 'journey' ? item.journey.journeyId : item.entry.eventType))).toEqual(['first', 'orphan', 'CHANNEL_EXPIRED'])
  })
})
