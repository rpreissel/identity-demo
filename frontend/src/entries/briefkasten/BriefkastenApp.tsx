import { useEffect, useState, type ReactNode } from 'react'
import { ErrorCard } from '../../components/ErrorCard'
import '../../App.css'
import { ChannelNav } from '../../components/ChannelNav'
import { AreaHead, SimBand } from '../../components/SimBand'
import { outboxApi, type SentMail, type SentSms } from '../../outboxApi'
import { personenverzeichnisApi, type Brief, type RegisterPerson, type Vorgang } from '../../personenverzeichnisApi'
import { language, t } from '../../texts'

type Kind = 'brief' | 'sms' | 'mail'

/** One thing sent to a person, whatever the way: a letter, an SMS or an e-mail. */
interface MailboxEntry {
  key: string
  kind: Kind
  /** The person's name when the register knows it, else the number or address. */
  to: string
  /** Under the name: the number or address, or what a letter carries when it is not a Freischaltcode. */
  detail?: string
  code: string
  at: string
}

/** How often the page looks again - codes arrive while another window is used. */
const REFRESH_MS = 3000

const digits = (value: string | undefined) => (value ?? '').replace(/\D/g, '')

function fullName(p: RegisterPerson | undefined): string {
  return p ? [p.vorname, p.name].filter(Boolean).join(' ') || `#${p.id}` : t('unbekannt')
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString(language(), { dateStyle: 'medium', timeStyle: 'short' })
}

/** Name and value when the register knows whose number or address it is, else the value alone. */
function addressed(value: string, person: RegisterPerson | undefined): Pick<MailboxEntry, 'to' | 'detail'> {
  return person ? { to: fullName(person), detail: value } : { to: value }
}

const ICONS: Record<Kind, ReactNode> = {
  sms: (
    <svg width="22" height="22" viewBox="0 0 22 22" fill="none">
      <rect x="6" y="2" width="10" height="18" rx="2.5" stroke="currentColor" strokeWidth="1.7" />
      <path d="M10 17h2" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" />
    </svg>
  ),
  mail: (
    <svg width="22" height="22" viewBox="0 0 22 22" fill="none">
      <rect x="2.5" y="5" width="17" height="12" rx="2" stroke="currentColor" strokeWidth="1.7" />
      <path d="M3 6l8 6 8-6" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round" />
    </svg>
  ),
  brief: (
    <svg width="22" height="22" viewBox="0 0 22 22" fill="none">
      <path d="M3 8l8-5 8 5v10H3z" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round" />
      <path d="M3 8l8 5 8-5" stroke="currentColor" strokeWidth="1.7" strokeLinejoin="round" />
    </svg>
  ),
}

function title(entry: MailboxEntry): string {
  switch (entry.kind) {
    case 'sms':
      return t('SMS an {name}', { name: entry.to })
    case 'mail':
      return t('E-Mail an {name}', { name: entry.to })
    case 'brief':
      return t('Brief an {name}', { name: entry.to })
  }
}

/**
 * The recipients' side of the demo: every letter, SMS and e-mail sent to a test person, where a
 * real person would find it on paper, on the phone or in the inbox. Reads the outboxes of the
 * simulated person register, SMS provider and mail server, all of them demo-only (ADR-36).
 */
export function BriefkastenApp() {
  const [personen, setPersonen] = useState<RegisterPerson[]>([])
  const [briefe, setBriefe] = useState<Brief[]>([])
  const [sms, setSms] = useState<SentSms[]>([])
  const [mails, setMails] = useState<SentMail[]>([])
  const [error, setError] = useState<string | null>(null)
  const [vorgaenge, setVorgaenge] = useState<Vorgang[]>([])
  const [filter, setFilter] = useState<Kind | 'alle'>('alle')

  // Names for the processes a one-time password letter is for; without them the id is shown.
  useEffect(() => {
    personenverzeichnisApi.vorgaenge().then(setVorgaenge).catch(() => {})
  }, [])

  useEffect(() => {
    let active = true
    const load = () => {
      personenverzeichnisApi.personen().then((p) => active && setPersonen(p)).catch((e: Error) => setError(e.message))
      personenverzeichnisApi.briefe().then((b) => active && setBriefe(b)).catch((e: Error) => setError(e.message))
      // Separate simulations: one missing leaves the rest usable.
      outboxApi.sms().then((m) => active && setSms(m)).catch(() => {})
      outboxApi.mail().then((m) => active && setMails(m)).catch(() => {})
    }
    load()
    const timer = window.setInterval(load, REFRESH_MS)
    return () => {
      active = false
      window.clearInterval(timer)
    }
  }, [])

  const entries: MailboxEntry[] = [
    ...briefe.map((b) => ({
      key: `brief-${b.id}`, kind: 'brief' as const, code: b.code, at: b.versandtAm,
      to: fullName(personen.find((p) => p.id === b.personId)),
      detail: b.art === 'EINMALKENNWORT' ? t('Einmalkennwort für {vorgang}', { vorgang: vorgaenge.find((v) => v.id === b.vorgang)?.name ?? b.vorgang ?? '' }) : t('Freischaltcode'),
    })),
    ...sms.map((m) => ({
      key: `sms-${m.sequence}`, kind: 'sms' as const, code: m.tan, at: m.sentAt,
      ...addressed(m.phoneNumber, personen.find((p) => p.mobilnummer && digits(p.mobilnummer) === digits(m.phoneNumber))),
    })),
    ...mails.map((m) => ({
      key: `mail-${m.sequence}`, kind: 'mail' as const, code: m.code, at: m.sentAt,
      ...addressed(m.address, personen.find((p) => p.email?.toLowerCase() === m.address.toLowerCase())),
    })),
  ].sort((a, b) => b.at.localeCompare(a.at))

  const shown = filter === 'alle' ? entries : entries.filter((e) => e.kind === filter)
  const filters: { key: Kind | 'alle'; label: string }[] = [
    { key: 'alle', label: t('Alle') },
    { key: 'sms', label: t('SMS') },
    { key: 'mail', label: t('E-Mail') },
    { key: 'brief', label: t('Briefe') },
  ]

  return (
    <div className="web-shell channel-mail">
      <ChannelNav area="mail" />
      <SimBand label={t('Simuliert · Seite der Empfänger')}>
        {t('Was an Testpersonen verschickt wird, landet hier statt auf dem Handy, im E-Mail-Postfach oder im Briefkasten zu Hause.')}
      </SimBand>
      <main className="area-page">
        <AreaHead kicker={t('Briefkasten')} title={t('Posteingang')}>
          <div className="chip-filter" role="group" aria-label={t('Art der Nachricht')}>
            {filters.map((f) => (
              <button key={f.key} className={filter === f.key ? 'on' : ''} aria-pressed={filter === f.key} onClick={() => setFilter(f.key)}>
                {f.label}
                <span className="chip-count">{f.key === 'alle' ? entries.length : entries.filter((e) => e.kind === f.key).length}</span>
              </button>
            ))}
          </div>
        </AreaHead>
        {error && <ErrorCard title={t('Fehler')}>{error}</ErrorCard>}
        {shown.length === 0 ? (
          <p className="area-empty">{t('Noch nichts verschickt.')}</p>
        ) : (
          <ul className="mailbox-list" aria-label={t('Alle Briefe, SMS und E-Mails an Personen, neueste zuerst.')}>
            {shown.map((entry) => (
              <li key={entry.key} className={`mailbox-item mailbox-item--${entry.kind}`}>
                <span className="mailbox-kind" aria-hidden="true">
                  {ICONS[entry.kind]}
                </span>
                <span className="mailbox-to">
                  <strong>{title(entry)}</strong>
                  {entry.detail && <span>{entry.detail}</span>}
                </span>
                <span className="mailbox-code">{entry.code}</span>
                <span className="mailbox-when">{formatDate(entry.at)}</span>
                <CopyButton value={entry.code} />
              </li>
            ))}
          </ul>
        )}
      </main>
    </div>
  )
}

/** Copies the code; says so for a moment. Without clipboard access (http, old browser) it stays quiet. */
function CopyButton({ value }: { value: string }) {
  const [copied, setCopied] = useState(false)

  function copy() {
    navigator.clipboard
      ?.writeText(value)
      .then(() => {
        setCopied(true)
        window.setTimeout(() => setCopied(false), 1500)
      })
      .catch(() => {})
  }

  return (
    <button className="secondary small mailbox-copy" onClick={copy} aria-label={t('{code} kopieren', { code: value })}>
      {copied ? t('Kopiert') : t('Kopieren')}
    </button>
  )
}
