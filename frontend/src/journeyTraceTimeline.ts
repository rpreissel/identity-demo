import type { JourneyTraceEntryView } from './types'

/**
 * One channel's trace as the admin page draws it: its journeys in start order, each with its
 * steps, and every sub-journey inside its parent right after the step that required it - not as a
 * block of its own at the end, where it would look unrelated (docs/04-orchestrierung.md #7).
 */
export interface TimelineJourney {
  journeyId: string
  parentJourneyId?: string
  intent?: string
  entries: JourneyTraceEntryView[]
  /** The steps, with sub-journeys placed between them. */
  parts: TimelinePart[]
}

export type TimelinePart = { kind: 'step'; entry: JourneyTraceEntryView } | { kind: 'subJourney'; journey: TimelineJourney }

/** A top-level journey, or an entry of the channel outside any journey (e.g. EXPIRED). */
export type TimelineItem = { kind: 'journey'; journey: TimelineJourney } | { kind: 'channelEvent'; entry: JourneyTraceEntryView }

/**
 * The parent step a sub-journey belongs after. The trace links the sub-journey only to its parent
 * (`parentJourneyId`), not to the step. That step is the parent's last `RequireSubJourney`
 * decision logged no later than the sub-journey's start; without one, the last step before it.
 * -1 places it before all steps.
 */
function anchorIndex(parent: JourneyTraceEntryView[], childStart: number): number {
  let lastBefore = -1
  let lastRequire = -1
  parent.forEach((entry, index) => {
    if (Date.parse(entry.createdAt) > childStart) return
    lastBefore = index
    if (entry.detail.decision === 'RequireSubJourney') lastRequire = index
  })
  return lastRequire >= 0 ? lastRequire : lastBefore
}

/** [entries] oldest first, as `GET .../channel-sessions/{id}` returns them. */
export function buildTimeline(entries: JourneyTraceEntryView[]): TimelineItem[] {
  const byJourney = new Map<string, JourneyTraceEntryView[]>()
  for (const entry of entries) {
    if (!entry.journeyId) continue
    byJourney.set(entry.journeyId, [...(byJourney.get(entry.journeyId) ?? []), entry])
  }

  const children = new Map<string, string[]>()
  const roots: string[] = []
  for (const [journeyId, steps] of byJourney) {
    const parentId = steps[0].parentJourneyId
    if (parentId && byJourney.has(parentId)) children.set(parentId, [...(children.get(parentId) ?? []), journeyId])
    else roots.push(journeyId)
  }

  function journeyOf(journeyId: string): TimelineJourney {
    const steps = byJourney.get(journeyId)!
    const subs = (children.get(journeyId) ?? []).map(journeyOf)
    const after = new Map<number, TimelineJourney[]>()
    for (const sub of subs) {
      const index = anchorIndex(steps, Date.parse(sub.entries[0].createdAt))
      after.set(index, [...(after.get(index) ?? []), sub])
    }
    const subParts = (index: number): TimelinePart[] => (after.get(index) ?? []).map((journey) => ({ kind: 'subJourney', journey }))
    const parts: TimelinePart[] = [...subParts(-1)]
    steps.forEach((entry, index) => parts.push({ kind: 'step', entry }, ...subParts(index)))
    return { journeyId, parentJourneyId: steps[0].parentJourneyId, intent: steps[0].intent, entries: steps, parts }
  }

  // Compared as instants: the backend writes as many fraction digits as it has, so the strings of
  // `…09Z` and `…09.001Z` do not sort like the times. Equal instants keep their logged order.
  const items: { at: number; item: TimelineItem }[] = [
    ...roots.map((journeyId) => ({ at: Date.parse(byJourney.get(journeyId)![0].createdAt), item: { kind: 'journey', journey: journeyOf(journeyId) } as TimelineItem })),
    ...entries.filter((entry) => !entry.journeyId).map((entry) => ({ at: Date.parse(entry.createdAt), item: { kind: 'channelEvent', entry } as TimelineItem })),
  ]
  return items.sort((a, b) => a.at - b.at).map(({ item }) => item)
}

/** Every step of [journey], its sub-journeys' included - for counting matches in the view. */
export function allEntries(journey: TimelineJourney): JourneyTraceEntryView[] {
  return journey.parts.flatMap((part) => (part.kind === 'step' ? [part.entry] : allEntries(part.journey)))
}
