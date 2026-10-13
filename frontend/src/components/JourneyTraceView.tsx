import { useEffect, useState, type FormEvent } from 'react'
import { describeError } from '../api'
import { channelTypeLabel, dateTimeFormat, durationLabel, intentLabel, outcomeLabel } from '../journeyTraceText'
import { t } from '../texts'
import { accountName, matchKindLabel, matchLabel } from '../accountText'
import type {
  AccountLookup,
  AccountMatch,
  JourneyTraceChannel,
  JourneyTraceOutcome,
  JourneyTracePage,
  JourneyTraceSearch,
  JourneyTraceSummary,
} from '../types'
import { JourneyTraceChannelView } from './JourneyTraceChannelView'

interface Props {
  /** The admin page's search (`searchAdminJourneys`). */
  search: (search: JourneyTraceSearch) => Promise<JourneyTracePage>
  /** One channel's entries (`fetchAdminChannelTrace`). */
  fetchChannel: (channelSessionId: string) => Promise<JourneyTraceChannel>
  /** The accounts behind an email address, KVNR, partner number, Versicherungsnummer or account number (`lookupAdminAccount`). */
  lookupAccount: (query: string) => Promise<AccountLookup>
  /** Opens with this account's journeys, e.g. from the account view. */
  initialAccountId?: number
}

const INTENTS = [
  'FAST_ACCESS', 'REGISTER', 'LOOKUP_LOGIN', 'WEB_SELECT_METHOD', 'STEP_UP', 'MANAGE_AUTH_METHODS', 'CONFIRM_PEER_LOGIN',
  'DELETE_ACCOUNT',
]
const OUTCOMES: JourneyTraceOutcome[] = ['SUCCEEDED', 'FAILED', 'CANCELLED', 'LOGGED_OUT', 'OPEN']
/** Hours back; the trace itself is kept 14 days. */
const RANGES = [1, 24, 24 * 7, 24 * 14]
const PAGE_SIZE = 50

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function rangeLabel(hours: number): string {
  if (hours === 1) return t('Letzte Stunde')
  if (hours === 24) return t('Letzte 24 Stunden')
  return t('Letzte {tage} Tage', { tage: hours / 24 })
}

interface Form {
  who: string
  intent: string
  outcome: string
  hours: number
}

const EMPTY_FORM: Form = { who: '', intent: '', outcome: '', hours: 24 }

/**
 * The admin page's Journey-Trace tab (docs/04-orchestrierung.md): a search over all journeys,
 * then one channel's steps in detail. It searches instead of listing the newest entries: at 20
 * million accounts those are a few seconds of traffic (docs/14-stand-und-weg-zur-produktion.md).
 * A person is found by an identifier, one indexed lookup in the backend, never from a list of all
 * accounts. The trace is for troubleshooting and support; the audit record is the change log.
 */
export function JourneyTraceView({ search, fetchChannel, lookupAccount, initialAccountId }: Props) {
  const [form, setForm] = useState<Form>(initialAccountId != null ? { ...EMPTY_FORM, who: String(initialAccountId) } : EMPTY_FORM)
  const [results, setResults] = useState<JourneyTraceSummary[]>([])
  const [page, setPage] = useState<JourneyTracePage | null>(null)
  const [lastSearch, setLastSearch] = useState<JourneyTraceSearch | null>(null)
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [selected, setSelected] = useState<JourneyTraceSummary | null>(null)
  /** More than one account matched (eight digits: Versicherungsnummer and account number); the reader picks. */
  const [choices, setChoices] = useState<AccountMatch[]>([])
  const [account, setAccount] = useState<AccountMatch | null>(null)

  // The newest journeys of the last day, before anything is asked for - or the given account's.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(() => run({ ...filters(EMPTY_FORM), accountId: initialAccountId }), [search, initialAccountId])

  function filters(f: Form): JourneyTraceSearch {
    return {
      intent: f.intent || undefined,
      outcome: (f.outcome || undefined) as JourneyTraceOutcome | undefined,
      from: new Date(Date.now() - f.hours * 3600_000).toISOString(),
      limit: PAGE_SIZE,
    }
  }

  /** A UUID may name a journey or a channel: tried as a journey first, then as a channel. Returns the search that found it. */
  async function find(query: JourneyTraceSearch): Promise<[JourneyTracePage, JourneyTraceSearch]> {
    const found = await search(query)
    if (query.journeyId && !query.cursor && found.journeys.length === 0) {
      const asChannel = { ...query, journeyId: undefined, channelSessionId: query.journeyId }
      return [await search(asChannel), asChannel]
    }
    return [found, query]
  }

  function run(query: JourneyTraceSearch, append = false) {
    setLoading(true)
    setError(null)
    find(query)
      .then(([next, used]) => {
        setPage(next)
        setLastSearch(used)
        setResults((previous) => (append ? [...previous, ...next.journeys] : next.journeys))
        if (!append && next.journeys.length === 1) setSelected(next.journeys[0])
      })
      .catch((err) => setError(describeError(t('Journey-Trace laden fehlgeschlagen'), err)))
      .finally(() => setLoading(false))
  }

  function searchAccount(match: AccountMatch) {
    setChoices([])
    setAccount(match)
    run({ ...filters(form), accountId: match.accountId })
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    setSelected(null)
    setChoices([])
    setAccount(null)
    const who = form.who.trim()
    if (!who) return run(filters(form))
    if (UUID.test(who)) return run({ ...filters(form), journeyId: who })
    setLoading(true)
    setError(null)
    lookupAccount(who)
      .then(({ matches }) => {
        if (matches.length === 1) return searchAccount(matches[0])
        setLoading(false)
        if (matches.length === 0) setError(t('Zu dieser Angabe gibt es kein Konto.'))
        else setChoices(matches)
      })
      .catch((err) => {
        setLoading(false)
        setError(describeError(t('Konto suchen fehlgeschlagen'), err))
      })
  }

  const update = (patch: Partial<Form>) => setForm((previous) => ({ ...previous, ...patch }))

  return (
    <div className="card journey-trace-card">
      <h2>{t('Journey-Trace')}</h2>
      <p>
        {t(
          'Suche nach E-Mail-Adresse, KVNR, Partnernummer, Versicherungsnummer, Kontonummer oder ID und öffne eine Journey, ' +
            'um ihre Schritte zu sehen. Sub-Journeys stehen an dem Schritt, der sie ausgelöst hat. Ablaufprotokoll für ' +
            'Fehlersuche und Support, 14 Tage aufbewahrt. Belege für die Revision stehen im Änderungsprotokoll des Kontos.',
        )}
      </p>

      <form className="jt-search" onSubmit={submit} role="search">
        <label className="jt-field jt-field--wide">
          {t('Person oder ID')}
          <input
            type="search"
            value={form.who}
            placeholder={t('E-Mail, KVNR, Partnernr., Versicherungsnr., Kontonr. oder Journey-ID')}
            onChange={(e) => update({ who: e.target.value })}
          />
        </label>
        <label className="jt-field">
          {t('Anliegen')}
          <select value={form.intent} onChange={(e) => update({ intent: e.target.value })}>
            <option value="">{t('Alle Anliegen')}</option>
            {INTENTS.map((intent) => (
              <option key={intent} value={intent}>
                {intentLabel(intent)}
              </option>
            ))}
          </select>
        </label>
        <label className="jt-field">
          {t('Ergebnis')}
          <select value={form.outcome} onChange={(e) => update({ outcome: e.target.value })}>
            <option value="">{t('Alle Ergebnisse')}</option>
            {OUTCOMES.map((outcome) => (
              <option key={outcome} value={outcome}>
                {outcomeLabel(outcome)}
              </option>
            ))}
          </select>
        </label>
        <label className="jt-field">
          {t('Zeitraum')}
          <select value={form.hours} onChange={(e) => update({ hours: Number(e.target.value) })}>
            {RANGES.map((hours) => (
              <option key={hours} value={hours}>
                {rangeLabel(hours)}
              </option>
            ))}
          </select>
        </label>
        <button type="submit" disabled={loading}>
          {t('Suchen')}
        </button>
      </form>

      {error && (
        <div className="card error-card">
          <p>{error}</p>
        </div>
      )}

      {choices.length > 0 && (
        <div className="jt-choices" role="group" aria-label={t('Mehrere Konten passen')}>
          <p>{t('Mehrere Konten passen zu dieser Angabe. Welches ist gemeint?')}</p>
          {choices.map((match) => (
            <button key={match.accountId} type="button" className="secondary" onClick={() => searchAccount(match)}>
              {matchLabel(match)}
            </button>
          ))}
        </div>
      )}

      {account && (
        <p className="jt-muted">
          {t('Journeys von {name} (Konto {id}), gefunden über {merkmal}', {
            name: accountName(account),
            id: account.accountId,
            merkmal: matchKindLabel(account.matchedBy),
          })}
        </p>
      )}

      <div className="jt-results-scroll">
        <table className="jt-results">
          <thead>
            <tr>
              <th>{t('Start')}</th>
              <th>{t('Anliegen')}</th>
              <th>{t('Person')}</th>
              <th>{t('Kanal')}</th>
              <th>{t('Ergebnis')}</th>
              <th>{t('Dauer')}</th>
              <th>{t('Schritte')}</th>
            </tr>
          </thead>
          <tbody>
            {results.map((journey) => (
              <tr key={journey.journeyId} aria-selected={selected?.journeyId === journey.journeyId}>
                <td className="jt-mono">{dateTimeFormat(journey.startedAt)}</td>
                <td>
                  <button type="button" className="jt-link" onClick={() => setSelected(journey)}>
                    {intentLabel(journey.intent)}
                  </button>
                  {journey.parentJourneyId && <span className="jt-pill jt-pill--sub">{t('Sub-Journey')}</span>}
                </td>
                <td>{journey.displayName ?? (journey.accountId != null ? t('Konto {id}', { id: journey.accountId }) : t('ohne Konto'))}</td>
                <td>{channelTypeLabel(journey.channelType)}</td>
                <td>
                  <span className={`jt-pill jt-pill--${journey.outcome.toLowerCase()}`}>{outcomeLabel(journey.outcome)}</span>
                </td>
                <td className="jt-mono">{durationLabel(journey.startedAt, journey.lastActivityAt)}</td>
                <td>
                  {journey.stepCount}
                  {journey.failedAttempts > 0 && (
                    <span className="jt-pill jt-pill--failed">{t('{anzahl} Fehlversuche', { anzahl: journey.failedAttempts })}</span>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="jt-results-footer">
        {loading && <span>{t('Lädt…')}</span>}
        {!loading && page && (
          <span>
            {results.length === 0 ? t('Keine Journeys gefunden.') : t('{anzahl} Journeys', { anzahl: results.length })}
            {page.searchedUntil && <> · {t('durchsucht bis {zeit}', { zeit: dateTimeFormat(page.searchedUntil) })}</>}
          </span>
        )}
        {page?.nextCursor && lastSearch && (
          <button type="button" className="secondary small" disabled={loading} onClick={() => run({ ...lastSearch, cursor: page.nextCursor! }, true)}>
            {t('Ältere laden')}
          </button>
        )}
      </div>

      {selected && (
        <JourneyTraceChannelView
          key={selected.channelSessionId}
          channelSessionId={selected.channelSessionId}
          focusJourneyId={selected.journeyId}
          fetchChannel={fetchChannel}
        />
      )}
    </div>
  )
}
