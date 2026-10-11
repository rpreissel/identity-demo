import { useEffect, useMemo, useState } from 'react'
import { describeError } from '../api'
import {
  STEP_CATEGORIES,
  categoryLabel,
  channelTypeLabel,
  dateTimeFormat,
  durationLabel,
  decisionOf,
  detailTags,
  intentLabel,
  outcomeLabel,
  stateLabel,
  stepCategory,
  stepTitle,
  type StepCategory,
} from '../journeyTraceText'
import { allEntries, buildTimeline, type TimelineJourney } from '../journeyTraceTimeline'
import { language, t } from '../texts'
import type { JourneyTraceChannel, JourneyTraceEntryView, JourneyTraceSummary } from '../types'

const LIVE_INTERVAL_MS = 5000

interface Props {
  channelSessionId: string
  /** The journey picked in the search: marked and scrolled to. */
  focusJourneyId?: string
  fetchChannel: (channelSessionId: string) => Promise<JourneyTraceChannel>
}

const timeFormat = (iso: string) => new Date(iso).toLocaleTimeString(language(), { timeStyle: 'medium' })

/** What the reader narrowed the steps to; one channel is small enough to filter here. */
interface StepFilter {
  text: string
  hidden: Set<StepCategory>
}

function entryKey(entry: JourneyTraceEntryView): string {
  return `${entry.journeyId ?? 'channel'}:${entry.createdAt}:${entry.eventType}`
}

/**
 * One channel's journey trace: its journeys in start order, each step as a sentence with the
 * decision it led to and its details as labelled tags, sub-journeys inside the step that required
 * them. Live refresh only here, for the one channel someone is watching.
 */
export function JourneyTraceChannelView({ channelSessionId, focusJourneyId, fetchChannel }: Props) {
  const [channel, setChannel] = useState<JourneyTraceChannel | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [liveOn, setLiveOn] = useState(false)
  const [technical, setTechnical] = useState(false)
  const [filter, setFilter] = useState<StepFilter>({ text: '', hidden: new Set() })
  const [open, setOpen] = useState<Set<string>>(new Set())

  function load() {
    fetchChannel(channelSessionId)
      .then((next) => {
        setChannel(next)
        setError(null)
      })
      .catch((err) => setError(describeError(t('Journey-Trace laden fehlgeschlagen'), err)))
  }

  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(load, [channelSessionId, fetchChannel])

  useEffect(() => {
    if (!liveOn) return
    const timer = window.setInterval(load, LIVE_INTERVAL_MS)
    return () => window.clearInterval(timer)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [liveOn, channelSessionId, fetchChannel])

  useEffect(() => {
    if (channel && focusJourneyId) document.getElementById(`jt-journey-${focusJourneyId}`)?.scrollIntoView?.({ block: 'nearest' })
  }, [channel, focusJourneyId])

  const timeline = useMemo(() => buildTimeline(channel?.entries ?? []), [channel])
  const summaries = useMemo(() => new Map((channel?.journeys ?? []).map((s) => [s.journeyId, s])), [channel])

  if (error) {
    return (
      <div className="card error-card">
        <p>{error}</p>
      </div>
    )
  }
  if (!channel) return <p>{t('Lädt…')}</p>

  const needle = filter.text.trim().toLowerCase()
  const visible = (entry: JourneyTraceEntryView) => {
    if (filter.hidden.has(stepCategory(entry))) return false
    if (!needle) return true
    const haystack = [
      stepTitle(entry),
      decisionOf(entry)?.text ?? '',
      entry.eventType,
      entry.journeyState ?? '',
      ...detailTags(entry.detail).flatMap((tag) => [tag.label, tag.value, tag.key]),
    ]
    return haystack.join(' ').toLowerCase().includes(needle)
  }
  const counts = new Map<StepCategory, number>()
  for (const entry of channel.entries) counts.set(stepCategory(entry), (counts.get(stepCategory(entry)) ?? 0) + 1)
  const shown = channel.entries.filter(visible).length

  const toggle = (key: string) =>
    setOpen((previous) => {
      const next = new Set(previous)
      if (next.has(key)) next.delete(key)
      else next.add(key)
      return next
    })

  const renderStep = (entry: JourneyTraceEntryView) => {
    const key = entryKey(entry)
    const decision = decisionOf(entry)
    const tags = detailTags(entry.detail)
    const isOpen = open.has(key)
    return (
      <li key={key} className={`jt-step jt-step--${stepCategory(entry)}`}>
        <span className="jt-step-time">{timeFormat(entry.createdAt)}</span>
        <span className="jt-step-dot" aria-hidden="true" />
        <div className="jt-step-body">
          <div className="jt-step-title">
            {stepTitle(entry)}
            {technical && <code className="jt-tech">{entry.eventType}</code>}
          </div>
          {decision && (
            <div className={`jt-step-decision jt-step-decision--${decision.tone}`}>
              <span aria-hidden="true">↳ </span>
              {decision.text}
            </div>
          )}
          {tags.length > 0 && !isOpen && (
            <ul className="jt-tags">
              {tags.map((tag) => (
                <li key={tag.key} title={tag.explanation}>
                  <span className="jt-tag-label">{technical ? tag.key : tag.label}</span>
                  <span className="jt-tag-value">{tag.value}</span>
                </li>
              ))}
            </ul>
          )}
          {isOpen && (
            <div className="jt-step-details">
              {tags.length > 0 && (
                <dl>
                  {tags.map((tag) => (
                    <div key={tag.key}>
                      <dt>
                        {tag.label} <code>{tag.key}</code>
                      </dt>
                      <dd>
                        <span className="jt-tag-value">{tag.value}</span>
                        {tag.explanation && <span className="jt-tag-explanation">{tag.explanation}</span>}
                      </dd>
                    </div>
                  ))}
                </dl>
              )}
              <pre>{JSON.stringify({ eventType: entry.eventType, journeyState: entry.journeyState, detail: entry.detail }, null, 2)}</pre>
            </div>
          )}
        </div>
        <div className="jt-step-state">
          <span>{t('Zustand')}</span>
          <strong>{stateLabel(entry.intent, entry.journeyState)}</strong>
          {technical && entry.journeyState && <code>{entry.journeyState}</code>}
        </div>
        <button type="button" className="jt-step-toggle secondary small" aria-expanded={isOpen} onClick={() => toggle(key)}>
          {isOpen ? t('Weniger') : t('Details')}
        </button>
      </li>
    )
  }

  const renderJourney = (journey: TimelineJourney, depth: number) => {
    const summary: JourneyTraceSummary | undefined = summaries.get(journey.journeyId)
    const matching = allEntries(journey).filter(visible).length
    return (
      <section
        key={journey.journeyId}
        id={`jt-journey-${journey.journeyId}`}
        className={`jt-journey${depth > 0 ? ' jt-journey--sub' : ''}${journey.journeyId === focusJourneyId ? ' jt-journey--focus' : ''}`}
      >
        <header className="jt-journey-header">
          {depth > 0 && <span className="jt-pill jt-pill--sub">{t('Sub-Journey, Voraussetzung')}</span>}
          <h4>{intentLabel(journey.intent)}</h4>
          {summary && <span className={`jt-pill jt-pill--${summary.outcome.toLowerCase()}`}>{outcomeLabel(summary.outcome)}</span>}
          {summary && (
            <span className="jt-muted">
              {timeFormat(summary.startedAt)}–{timeFormat(summary.lastActivityAt)} · {durationLabel(summary.startedAt, summary.lastActivityAt)} ·{' '}
              {t('{anzahl} Schritte', { anzahl: summary.stepCount })}
            </span>
          )}
          {technical && (
            <code className="jt-tech">
              {journey.intent} · {journey.journeyId}
            </code>
          )}
        </header>
        {matching === 0 ? (
          <p className="jt-muted jt-none">{t('Keine passenden Schritte.')}</p>
        ) : (
          <ol className="jt-steps">
            {journey.parts.map((part) =>
              part.kind === 'step' ? (
                visible(part.entry) && renderStep(part.entry)
              ) : (
                <li key={part.journey.journeyId} className="jt-sub-slot">
                  {renderJourney(part.journey, depth + 1)}
                </li>
              ),
            )}
          </ol>
        )}
      </section>
    )
  }

  const first = channel.entries[0]
  return (
    <section className="jt-channel" aria-label={t('Gerätesitzung')}>
      <header className="jt-channel-header">
        <h3>{channel.displayName ?? (channel.accountId != null ? t('Konto {id}', { id: channel.accountId }) : t('ohne Konto'))}</h3>
        <span className="jt-pill">{channelTypeLabel(channel.channelType)}</span>
        {first && <span className="jt-muted">{t('Gerätesitzung seit {datum}', { datum: dateTimeFormat(first.createdAt) })}</span>}
        {technical && <code className="jt-tech">channelSessionId {channel.channelSessionId}</code>}
      </header>

      <div className="jt-channel-controls">
        <label className="jt-field jt-field--wide">
          {t('In dieser Sitzung suchen')}
          <input
            type="search"
            value={filter.text}
            placeholder={t('z. B. SMS, LoA 3, Falscher Code')}
            onChange={(e) => setFilter({ ...filter, text: e.target.value })}
          />
        </label>
        <div className="jt-categories" role="group" aria-label={t('Schrittarten')}>
          {STEP_CATEGORIES.map((category) => (
            <button
              key={category}
              type="button"
              className={`jt-category jt-step--${category}`}
              aria-pressed={!filter.hidden.has(category)}
              onClick={() => {
                const hidden = new Set(filter.hidden)
                if (hidden.has(category)) hidden.delete(category)
                else hidden.add(category)
                setFilter({ ...filter, hidden })
              }}
            >
              <span className="jt-step-dot" aria-hidden="true" />
              {categoryLabel(category)} <span className="jt-muted">{counts.get(category) ?? 0}</span>
            </button>
          ))}
        </div>
        <label className="field-row">
          <input type="checkbox" checked={technical} onChange={(e) => setTechnical(e.target.checked)} />
          {t('Technische Namen')}
        </label>
        <label className="field-row">
          <input type="checkbox" checked={liveOn} onChange={(e) => setLiveOn(e.target.checked)} />
          {t('Live ({sekunden} s)', { sekunden: LIVE_INTERVAL_MS / 1000 })}
        </label>
      </div>

      <p className="jt-muted">
        {t('{sichtbar} von {gesamt} Schritten', { sichtbar: shown, gesamt: channel.entries.length })}
        {channel.truncated && <> · {t('Die Sitzung hat mehr Einträge; gezeigt werden die ältesten.')}</>}
      </p>

      {timeline.map((item) =>
        item.kind === 'journey' ? (
          renderJourney(item.journey, 0)
        ) : (
          visible(item.entry) && (
            <ol key={entryKey(item.entry)} className="jt-steps jt-steps--channel">
              {renderStep(item.entry)}
            </ol>
          )
        ),
      )}
    </section>
  )
}
