import { Fragment, useState, type FormEvent } from 'react'
import { describeError } from '../api'
import {
  accountName,
  attributeLabel,
  changeTypeLabel,
  claimSourceLabel,
  logDetailLine,
  matchKindLabel,
  matchLabel,
  methodLabel,
  retractionSourceLabel,
  roleExplanation,
  roleLabel,
  signInTypeLabel,
} from '../accountText'
import { acrLabel, channelTypeLabel, dateTimeFormat } from '../journeyTraceText'
import { t } from '../texts'
import type { AccountClaim, AccountDetails, AccountLookup, AccountMatch } from '../types'

interface Props {
  /** `lookupAdminAccount`: the accounts behind an email address, KVNR, partner number, Versicherungsnummer or account number. */
  lookupAccount: (query: string) => Promise<AccountLookup>
  /** `fetchAdminAccountDetails`. */
  fetchDetails: (accountId: number) => Promise<AccountDetails>
  /** `fetchAdminClaimValues`: the full values, logged in the backend. */
  fetchClaimValues: (accountId: number) => Promise<{ values: Record<string, string> }>
  /** Demo only: deletes the account; absent outside the demo. */
  deleteAccount?: (accountId: number) => Promise<void>
  /** Opens the journey trace for this account. */
  onOpenTrace?: (accountId: number) => void
}

type Tab = 'overview' | 'claims' | 'anchors' | 'methods' | 'logs'
type LogFilter = 'all' | 'change' | 'signIn'

/**
 * The admin page's account view (docs/10-frontend.md #6): find an account by an identifier - there
 * is no list of all accounts - then see what it knows and where each value came from. Claim values
 * arrive masked; showing them is a deliberate, logged step.
 */
export function AdminAccountView({ lookupAccount, fetchDetails, fetchClaimValues, deleteAccount, onOpenTrace }: Props) {
  const [query, setQuery] = useState('')
  const [choices, setChoices] = useState<AccountMatch[]>([])
  const [match, setMatch] = useState<AccountMatch | null>(null)
  const [details, setDetails] = useState<AccountDetails | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [tab, setTab] = useState<Tab>('overview')
  const [values, setValues] = useState<Record<string, string> | null>(null)
  const [showRetracted, setShowRetracted] = useState(true)
  const [openType, setOpenType] = useState<string | null>(null)
  const [logFilter, setLogFilter] = useState<LogFilter>('all')
  const [confirmDelete, setConfirmDelete] = useState(false)

  function open(found: AccountMatch) {
    setChoices([])
    setMatch(found)
    setValues(null)
    setOpenType(null)
    setConfirmDelete(false)
    setLoading(true)
    fetchDetails(found.accountId)
      .then(setDetails)
      .catch((err) => setError(describeError(t('Konto laden fehlgeschlagen'), err)))
      .finally(() => setLoading(false))
  }

  function submit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setNotice(null)
    setDetails(null)
    setMatch(null)
    setChoices([])
    if (!query.trim()) return
    setLoading(true)
    lookupAccount(query.trim())
      .then(({ matches }) => {
        setLoading(false)
        if (matches.length === 1) open(matches[0])
        else if (matches.length === 0) setError(t('Zu dieser Angabe gibt es kein Konto.'))
        else setChoices(matches)
      })
      .catch((err) => {
        setLoading(false)
        setError(describeError(t('Konto suchen fehlgeschlagen'), err))
      })
  }

  function reveal() {
    if (!details) return
    if (values) return setValues(null)
    fetchClaimValues(details.accountId)
      .then((result) => setValues(result.values))
      .catch((err) => setError(describeError(t('Werte laden fehlgeschlagen'), err)))
  }

  function remove() {
    if (!details || !deleteAccount) return
    deleteAccount(details.accountId)
      .then(() => {
        setNotice(t('Konto {id} gelöscht.', { id: details.accountId }))
        setDetails(null)
        setMatch(null)
      })
      .catch((err) => setError(describeError(t('Aktion fehlgeschlagen'), err)))
  }

  const visibleClaims = details ? details.claims.filter((claim) => showRetracted || !claim.retraction) : []

  const valueOf = (claim: AccountClaim) => {
    if (!claim.readable) return t('nicht mehr lesbar, Frist abgelaufen')
    return values?.[String(claim.id)] ?? claim.maskedValue ?? '–'
  }

  return (
    <div className="card account-view">
      <h2>{t('Konten')}</h2>
      <p>
        {t('Suchen Sie ein Konto über E-Mail-Adresse, KVNR, Partnernummer, Versicherungsnummer oder Kontonummer. Namen sind nicht suchbar.')}
      </p>
      <form className="jt-search" onSubmit={submit} role="search">
        <label className="jt-field jt-field--wide">
          {t('Person oder Konto')}
          <input
            type="search"
            value={query}
            placeholder={t('E-Mail, KVNR, Partnernr., Versicherungsnr. oder Kontonr.')}
            onChange={(e) => setQuery(e.target.value)}
          />
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
      {notice && <p className="hint">{notice}</p>}

      {choices.length > 0 && (
        <div className="jt-choices" role="group" aria-label={t('Mehrere Konten passen')}>
          <p>{t('Mehrere Konten passen zu dieser Angabe. Welches ist gemeint?')}</p>
          {choices.map((choice) => (
            <button key={choice.accountId} type="button" className="secondary" onClick={() => open(choice)}>
              {matchLabel(choice)}
            </button>
          ))}
        </div>
      )}

      {loading && <p>{t('Lädt…')}</p>}

      {details && match && (
        <section className="ac-account" aria-label={accountName(details)}>
          <header className="ac-head">
            <div>
              <div className="ac-title">
                <h3>{accountName(details)}</h3>
                <span className="jt-pill jt-pill--sub">{roleLabel(details.role)}</span>
              </div>
              <p className="jt-muted">
                {t('Konto {id}', { id: details.accountId })}
                {details.createdAt && <> · {t('angelegt am {datum}', { datum: dateTimeFormat(details.createdAt) })}</>} ·{' '}
                {t('gefunden über {merkmal}', { merkmal: matchKindLabel(match.matchedBy) })}
              </p>
            </div>
            <div className="ac-actions">
              {onOpenTrace && (
                <button type="button" className="secondary" onClick={() => onOpenTrace(details.accountId)}>
                  {t('Journey-Trace öffnen')}
                </button>
              )}
              {deleteAccount &&
                (confirmDelete ? (
                  <span className="value-with-action">
                    <button type="button" className="destructive small" onClick={remove}>
                      {t('Wirklich löschen')}
                    </button>
                    <button type="button" className="secondary small" onClick={() => setConfirmDelete(false)}>
                      {t('Abbrechen')}
                    </button>
                  </span>
                ) : (
                  <button type="button" className="secondary" onClick={() => setConfirmDelete(true)}>
                    {t('Konto löschen')}
                  </button>
                ))}
            </div>
          </header>

          <div className="ac-tabs" role="tablist">
            {(
              [
                ['overview', t('Übersicht'), ''],
                ['claims', t('Angaben'), String(details.claims.length)],
                ['anchors', t('Anker'), String(details.anchors.length)],
                ['methods', t('Verfahren und Geräte'), String(details.methods.length + details.devices.length)],
                ['logs', t('Protokolle'), String(details.changeLog.length + details.signIns.length)],
              ] as [Tab, string, string][]
            ).map(([key, label, count]) => (
              <button key={key} type="button" role="tab" className="ac-tab" aria-selected={tab === key} onClick={() => setTab(key)}>
                {label}
                {count && <span className="jt-muted"> {count}</span>}
              </button>
            ))}
          </div>

          {tab === 'overview' && (
            <dl className="ac-kv">
              <div>
                <dt>{t('Rolle')}</dt>
                <dd>
                  {roleLabel(details.role)}: {roleExplanation(details.role)}
                </dd>
              </div>
              {details.anchors.map((anchor) => (
                <div key={anchor.attributeType}>
                  <dt>{attributeLabel(anchor.attributeType)}</dt>
                  <dd className="jt-mono">{anchor.value}</dd>
                </div>
              ))}
              <div>
                <dt>{t('Anmeldeverfahren')}</dt>
                <dd>
                  {t('{aktiv} aktiv, {entfernt} entfernt', {
                    aktiv: details.methods.filter((m) => m.active).length,
                    entfernt: details.methods.filter((m) => !m.active).length,
                  })}
                </dd>
              </div>
              <div>
                <dt>{t('Verknüpfte Geräte')}</dt>
                <dd>{details.devices.length}</dd>
              </div>
              {details.signIns.length > 0 && (
                <div>
                  <dt>{t('Letztes Anmeldeereignis')}</dt>
                  <dd>
                    {signInTypeLabel(details.signIns.at(-1)!.signInType ?? '')} · {dateTimeFormat(details.signIns.at(-1)!.occurredAt)}
                  </dd>
                </div>
              )}
            </dl>
          )}

          {tab === 'claims' && (
            <>
              <div className="ac-toolbar">
                <p className="jt-muted">
                  {t(
                    'Jede Angabe, die je bestätigt wurde, mit Quelle und Sicherheitsniveau. Zurückgezogene stehen durchgestrichen da. ' +
                      'Nach Ablauf der Frist ist ein Wert nicht mehr lesbar; die Zeile bleibt. Ein Klick auf eine Zeile zeigt die Geschichte dieser Angabe.',
                  )}
                </p>
                <div className="ac-actions">
                  <button type="button" className="secondary small" aria-pressed={showRetracted} onClick={() => setShowRetracted(!showRetracted)}>
                    {showRetracted ? t('Zurückgezogene ausblenden') : t('Zurückgezogene zeigen')}
                  </button>
                  <button type="button" className="secondary small" aria-pressed={values !== null} onClick={reveal}>
                    {values ? t('Werte verbergen') : t('Werte anzeigen')}
                  </button>
                </div>
              </div>
              <p className="jt-muted">{t('Das Anzeigen der vollen Werte wird mit Ihrem Namen protokolliert.')}</p>
              <div className="jt-results-scroll">
                <table className="jt-results">
                  <thead>
                    <tr>
                      <th>{t('Angabe')}</th>
                      <th>{t('Wert')}</th>
                      <th>{t('Niveau')}</th>
                      <th>{t('Quelle')}</th>
                      <th>{t('Verfahren')}</th>
                      <th>{t('Seit')}</th>
                      <th>{t('Status')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {visibleClaims.map((claim) => (
                        <Fragment key={claim.id}>
                          <tr className={claim.retraction ? 'ac-retracted' : undefined}>
                            <td>
                              <button
                                type="button"
                                className="jt-link"
                                aria-expanded={openType === claim.attributeType}
                                onClick={() => setOpenType(openType === claim.attributeType ? null : claim.attributeType)}
                              >
                                {attributeLabel(claim.attributeType)}
                              </button>
                            </td>
                            <td className={claim.readable ? 'jt-mono ac-value' : 'ac-erased'}>{valueOf(claim)}</td>
                            <td>{acrLabel(claim.establishedAcr)}</td>
                            <td>{claimSourceLabel(claim.claimSource)}</td>
                            <td>{claim.method ? methodLabel(claim.method) : '–'}</td>
                            <td className="jt-mono">{dateTimeFormat(claim.establishedAt)}</td>
                            <td>
                              {claim.retraction ? (
                                <span className="jt-pill jt-pill--failed">{t('zurückgezogen')}</span>
                              ) : (
                                <span className="jt-pill jt-pill--succeeded">{t('gültig')}</span>
                              )}
                            </td>
                          </tr>
                          {openType === claim.attributeType && claim === visibleClaims.find((c) => c.attributeType === claim.attributeType) && (
                            <tr className="ac-history-row">
                              <td colSpan={7}>
                                <ClaimHistory type={claim.attributeType} details={details} valueOf={valueOf} />
                              </td>
                            </tr>
                          )}
                        </Fragment>
                      ))}
                  </tbody>
                </table>
              </div>
            </>
          )}

          {tab === 'anchors' && (
            <>
              <p className="jt-muted">
                {t('Anker sind die Werte, über die das System ein Konto eindeutig findet. Jeder Wert gehört höchstens einem Konto.')}
              </p>
              <div className="jt-results-scroll">
                <table className="jt-results">
                  <thead>
                    <tr>
                      <th>{t('Anker')}</th>
                      <th>{t('Wert')}</th>
                      <th>{t('Festgelegt mit')}</th>
                      <th>{t('Seit')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {details.anchors.map((anchor) => (
                      <tr key={anchor.attributeType}>
                        <td>
                          {attributeLabel(anchor.attributeType)} <code className="jt-tech">{anchor.attributeType}</code>
                        </td>
                        <td className="jt-mono">{anchor.value}</td>
                        <td>{acrLabel(anchor.establishedAcr)}</td>
                        <td className="jt-mono">{dateTimeFormat(anchor.establishedAt)}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            </>
          )}

          {tab === 'methods' && (
            <>
              <div className="jt-results-scroll">
                <table className="jt-results">
                  <thead>
                    <tr>
                      <th>{t('Verfahren')}</th>
                      <th>{t('Bezeichnung')}</th>
                      <th>{t('Eingerichtet mit')}</th>
                      <th>{t('An Gerät gebunden')}</th>
                      <th>{t('Eingerichtet')}</th>
                      <th>{t('Status')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {details.methods.map((method) => (
                      <tr key={method.id} className={method.active ? undefined : 'ac-retracted'}>
                        <td>
                          {methodLabel(method.method)} <code className="jt-tech">{method.method}</code>
                        </td>
                        <td>{method.label ?? '–'}</td>
                        <td>{acrLabel(method.enrolledUnderAcr)}</td>
                        <td className="jt-mono">{method.boundKeyRef ? `…${method.boundKeyRef.slice(-6)}` : t('nein')}</td>
                        <td className="jt-mono">{method.createdAt ? dateTimeFormat(method.createdAt) : '–'}</td>
                        <td>
                          {method.active ? (
                            <span className="jt-pill jt-pill--succeeded">{t('aktiv')}</span>
                          ) : (
                            <span className="jt-pill jt-pill--cancelled">
                              {method.deactivatedAt ? t('entfernt am {datum}', { datum: dateTimeFormat(method.deactivatedAt) }) : t('entfernt')}
                            </span>
                          )}
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
              <h4>{t('Verknüpfte Geräte')}</h4>
              {details.devices.length === 0 ? (
                <p className="jt-muted">{t('Keine verknüpften Geräte.')}</p>
              ) : (
                <div className="jt-results-scroll">
                  <table className="jt-results">
                    <thead>
                      <tr>
                        <th>{t('Geräteschlüssel')}</th>
                        <th>{t('Verknüpft')}</th>
                        <th>{t('Zuletzt bestätigt')}</th>
                      </tr>
                    </thead>
                    <tbody>
                      {details.devices.map((device) => (
                        <tr key={device.bindingKeyRef}>
                          <td className="jt-mono">…{device.bindingKeyRef.slice(-6)}</td>
                          <td className="jt-mono">{dateTimeFormat(device.linkedAt)}</td>
                          <td className="jt-mono">{dateTimeFormat(device.lastLinkedAt)}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}
            </>
          )}

          {tab === 'logs' && (
            <>
              <div className="jt-categories" role="group" aria-label={t('Protokoll')}>
                {(
                  [
                    ['all', t('Alle')],
                    ['change', t('Änderungsprotokoll')],
                    ['signIn', t('Anmeldeprotokoll')],
                  ] as [LogFilter, string][]
                ).map(([key, label]) => (
                  <button key={key} type="button" className="jt-category" aria-pressed={logFilter === key} onClick={() => setLogFilter(key)}>
                    {label}
                  </button>
                ))}
              </div>
              <div className="jt-results-scroll">
                <table className="jt-results">
                  <thead>
                    <tr>
                      <th>{t('Zeit')}</th>
                      <th>{t('Protokoll')}</th>
                      <th>{t('Ereignis')}</th>
                      <th>{t('Niveau')}</th>
                      <th>{t('Einzelheiten')}</th>
                    </tr>
                  </thead>
                  <tbody>
                    {[
                      ...(logFilter === 'signIn' ? [] : details.changeLog.map((entry) => ({ entry, change: true }))),
                      ...(logFilter === 'change' ? [] : details.signIns.map((entry) => ({ entry, change: false }))),
                    ]
                      .sort((a, b) => Date.parse(b.entry.occurredAt) - Date.parse(a.entry.occurredAt))
                      .map(({ entry, change }, index) => (
                        <tr key={index}>
                          <td className="jt-mono">{dateTimeFormat(entry.occurredAt)}</td>
                          <td>
                            <span className={change ? 'jt-pill jt-pill--sub' : 'jt-pill'}>{change ? t('Änderung') : t('Anmeldung')}</span>
                          </td>
                          <td>
                            {change ? changeTypeLabel(entry.changeType ?? '') : signInTypeLabel(entry.signInType ?? '')}{' '}
                            <code className="jt-tech">{entry.changeType ?? entry.signInType}</code>
                          </td>
                          <td>{acrLabel(entry.acr)}</td>
                          <td>
                            {[entry.channel ? channelTypeLabel(entry.channel) : '', entry.subject ?? '', logDetailLine(entry.details)]
                              .filter(Boolean)
                              .join(' · ') || '–'}
                          </td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
              <p className="jt-muted">{t('Das Änderungsprotokoll bleibt auch nach dem Löschen des Kontos erhalten.')}</p>
            </>
          )}
        </section>
      )}
    </div>
  )
}

const SAME_INSTANT_ORDER = { retraction: 0, claim: 1, anchor: 2 }

/** Every claim and retraction of one attribute type in time order: where the value came from and why it went. */
function ClaimHistory({ type, details, valueOf }: { type: string; details: AccountDetails; valueOf: (claim: AccountClaim) => string }) {
  const events = [
    ...details.claims
      .filter((claim) => claim.attributeType === type)
      .map((claim) => ({
        at: claim.establishedAt,
        kind: 'claim' as const,
        title: t('Bestätigt'),
        value: valueOf(claim),
        facts: [
          t('Quelle: {quelle}', { quelle: claimSourceLabel(claim.claimSource) }),
          claim.method ? t('Verfahren: {verfahren}', { verfahren: methodLabel(claim.method) }) : '',
          acrLabel(claim.establishedAcr),
        ],
      })),
    ...details.retractions
      .filter((retraction) => retraction.attributeType === type)
      .map((retraction) => ({
        at: retraction.retractedAt,
        kind: 'retraction' as const,
        title: t('Zurückgezogen'),
        value: '',
        facts: [
          t('durch: {wer}', { wer: retractionSourceLabel(retraction.source) }),
          retraction.reason ? t('Grund: {grund}', { grund: retraction.reason }) : '',
        ],
      })),
    ...details.anchors
      .filter((anchor) => anchor.attributeType === type)
      .map((anchor) => ({
        at: anchor.establishedAt,
        kind: 'anchor' as const,
        title: t('Als Anker festgelegt'),
        value: anchor.value,
        facts: [acrLabel(anchor.establishedAcr)],
      })),
  ]
    // In one instant the old value goes before the new one comes, and the anchor follows the claim.
    .sort((a, b) => Date.parse(a.at) - Date.parse(b.at) || SAME_INSTANT_ORDER[a.kind] - SAME_INSTANT_ORDER[b.kind])

  return (
    <ol className="ac-history" aria-label={t('Geschichte von {angabe}', { angabe: attributeLabel(type) })}>
      {events.map((event, index) => (
        <li key={index} className={`ac-history-step ac-history-step--${event.kind}`}>
          <span className="ac-history-dot" aria-hidden="true" />
          <div>
            <strong>{event.title}</strong> <span className="jt-mono jt-muted">{dateTimeFormat(event.at)}</span>
            {event.value && <div className="jt-mono">{event.value}</div>}
            <div className="jt-muted">{event.facts.filter(Boolean).join(' · ')}</div>
          </div>
        </li>
      ))}
    </ol>
  )
}
