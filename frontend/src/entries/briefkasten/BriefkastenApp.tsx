import { useEffect, useState } from 'react'
import '../../App.css'
import { ChannelNav } from '../../components/ChannelNav'
import { outboxApi, type SentMail, type SentSms } from '../../outboxApi'
import { personenverzeichnisApi, type Brief, type RegisterPerson, type Vorgang } from '../../personenverzeichnisApi'
import { language, t } from '../../texts'

/** One thing sent to a person, whatever the way: a letter, an SMS or an e-mail. */
interface MailboxEntry {
  key: string
  kind: 'brief' | 'sms' | 'mail'
  to: string
  code: string
  at: string
  /** What a letter carries, when it is not a Freischaltcode. */
  detail?: string
}

/** How often the page looks again - codes arrive while another window is used. */
const REFRESH_MS = 3000

const digits = (value: string | undefined) => (value ?? '').replace(/\D/g, '')

function fullName(p: RegisterPerson | undefined): string {
  return p ? [p.vorname, p.name].filter(Boolean).join(' ') || `#${p.id}` : t('unbekannt')
}

/** "Name (Wert)" when the register knows whose number or address it is, else just the value. */
function recipient(value: string, person: RegisterPerson | undefined): string {
  return person ? `${fullName(person)} (${value})` : value
}

function formatDate(iso: string): string {
  return new Date(iso).toLocaleString(language(), { dateStyle: 'medium', timeStyle: 'short' })
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
      detail: b.art === 'EINMALKENNWORT' ? t('Einmalkennwort für {vorgang}', { vorgang: vorgaenge.find((v) => v.id === b.vorgang)?.name ?? b.vorgang ?? '' }) : undefined,
    })),
    ...sms.map((m) => ({
      key: `sms-${m.sequence}`, kind: 'sms' as const, code: m.tan, at: m.sentAt,
      to: recipient(m.phoneNumber, personen.find((p) => p.mobilnummer && digits(p.mobilnummer) === digits(m.phoneNumber))),
    })),
    ...mails.map((m) => ({
      key: `mail-${m.sequence}`, kind: 'mail' as const, code: m.code, at: m.sentAt,
      to: recipient(m.address, personen.find((p) => p.email?.toLowerCase() === m.address.toLowerCase())),
    })),
  ].sort((a, b) => b.at.localeCompare(a.at))

  const kindLabel = { brief: `📮 ${t('Brief')}`, sms: `📱 ${t('SMS')}`, mail: `✉️ ${t('E-Mail')}` }

  return (
    <div className="web-shell channel-ext">
      <ChannelNav badge={`📬 ${t('Briefkasten')}`} />
      <div className="web-page">
        <div className="ext-banner">
          {t('Simuliert: die Seite der Empfänger. Was an Testpersonen verschickt wird, landet hier statt auf dem Handy, im E-Mail-Postfach oder im Briefkasten zu Hause.')}
        </div>
        {error && <div className="card error-card"><h2>{t('Fehler')}</h2><p>{error}</p></div>}
        <div className="card">
          <h2>{t('Posteingang')}</h2>
          <p>{t('Alle Briefe, SMS und E-Mails an Personen, neueste zuerst.')}</p>
          {entries.length === 0 ? (
            <p>{t('Noch nichts verschickt.')}</p>
          ) : (
            <div className="journey-trace-table-scroll">
              <table className="journey-trace-table">
                <thead>
                  <tr>
                    <th>{t('Art')}</th>
                    <th>{t('An')}</th>
                    <th>{t('Code')}</th>
                    <th>{t('Zeit')}</th>
                  </tr>
                </thead>
                <tbody>
                  {entries.map((entry) => (
                    <tr key={entry.key}>
                      <td>
                        {kindLabel[entry.kind]}
                        {entry.detail && <span className="mailbox-detail">{entry.detail}</span>}
                      </td>
                      <td>{entry.to}</td>
                      <td className="mailbox-code">{entry.code}</td>
                      <td>{formatDate(entry.at)}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
