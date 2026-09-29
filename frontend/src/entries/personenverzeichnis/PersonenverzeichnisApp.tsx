import { useCallback, useEffect, useState, type FormEvent } from 'react'
import '../../App.css'
import { ChannelNav, type NavTab } from '../../components/ChannelNav'
import { personenverzeichnisApi, type Brief, type Einladung, type Freischaltcode, type RegisterPerson, type Vorgang } from '../../personenverzeichnisApi'
import { language, t } from '../../texts'
import { Tx } from '../../Tx'
import { useHashTab } from '../../useHashTab'

type Tab = 'personen' | 'freischaltcodes' | 'einladungen'
const TAB_KEYS = ['personen', 'freischaltcodes', 'einladungen'] as const
const TABS: NavTab<Tab>[] = [
  { key: 'personen', label: t('Personen') },
  { key: 'freischaltcodes', label: t('Freischaltcodes') },
  { key: 'einladungen', label: t('Einladungen') },
]

const FIELDS: { key: keyof RegisterPerson; label: string; type?: string }[] = [
  { key: 'name', label: t('Name') },
  { key: 'vorname', label: t('Vorname') },
  { key: 'geburtsdatum', label: t('Geburtsdatum'), type: 'date' },
  { key: 'strasse', label: t('Straße') },
  { key: 'hausnummer', label: t('Hausnummer') },
  { key: 'plz', label: t('PLZ') },
  { key: 'ort', label: t('Ort') },
  { key: 'email', label: t('E-Mail-Adresse'), type: 'email' },
  { key: 'mobilnummer', label: t('Mobilnummer'), type: 'tel' },
]

function fullName(p: RegisterPerson | undefined): string {
  return p ? [p.vorname, p.name].filter(Boolean).join(' ') || `#${p.id}` : t('unbekannt')
}

/** "Straße Nr, PLZ Ort" - leaving out whatever part the register does not have. */
function address(p: RegisterPerson): string {
  const street = [p.strasse, p.hausnummer].filter(Boolean).join(' ')
  const city = [p.plz, p.ort].filter(Boolean).join(' ')
  return [street, city].filter(Boolean).join(', ') || '–'
}

function formatDate(iso: string | undefined): string {
  return iso ? new Date(iso).toLocaleString(language(), { dateStyle: 'medium', timeStyle: 'short' }) : '–'
}

function inOneYear(): string {
  const d = new Date()
  d.setFullYear(d.getFullYear() + 1)
  return d.toISOString().slice(0, 10)
}

/**
 * The simulated person register (ADR-31) - deliberately a page of its own, visibly a foreign
 * system: what is changed here reaches our application only the way a real register would, by
 * ident-fsc asking it. Nothing on this page talks to /orchestrator.
 */
export function PersonenverzeichnisApp() {
  const [tab, setTab] = useHashTab<Tab>(TAB_KEYS, 'personen')
  const [personen, setPersonen] = useState<RegisterPerson[]>([])
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(() => {
    personenverzeichnisApi.personen().then(setPersonen).catch((e: Error) => setError(e.message))
  }, [])
  useEffect(reload, [reload])

  return (
    <div className="web-shell channel-ext">
      <ChannelNav badge={`🏛️ ${t('Personenverzeichnis')}`} tabs={TABS} sub={tab} onSelectTab={setTab} />
      <div className="web-page">
        <div className="ext-banner">
          <Tx text="Simuliertes {fremdsystem}: das externe Personenverzeichnis." fremdsystem={<strong>{t('Fremdsystem')}</strong>} />{' '}
          <Tx
            text="Unsere Anwendung liest es nur über {schnittstelle} und fragt es beim Freischaltcode ({fsc}) und bei der Zuordnung ({zuordnung})."
            schnittstelle={<code>PersonDirectory</code>}
            fsc={<code>ident-fsc</code>}
            zuordnung={<code>ident-kvnr</code>}
          />{' '}
          {t('Was Sie hier ändern, meldet es an die Konten - und über sie an Keycloak.')}
        </div>
        {error && <div className="card error-card"><h2>{t('Fehler')}</h2><p>{error}</p></div>}
        {tab === 'personen' && <PersonenTab personen={personen} onChanged={reload} onError={setError} />}
        {tab === 'freischaltcodes' && <FreischaltcodesTab personen={personen} onError={setError} />}
        {tab === 'einladungen' && <EinladungenTab personen={personen} onError={setError} />}
      </div>
    </div>
  )
}

function PersonenTab({ personen, onChanged, onError }: { personen: RegisterPerson[]; onChanged: () => void; onError: (m: string | null) => void }) {
  // null = no form open; an object without id = new person.
  const [editing, setEditing] = useState<RegisterPerson | null>(null)

  async function save(e: FormEvent) {
    e.preventDefault()
    if (!editing) return
    try {
      if (editing.id) await personenverzeichnisApi.aendern(editing.id, editing)
      else await personenverzeichnisApi.anlegen(editing)
      onError(null)
      setEditing(null)
      onChanged()
    } catch (err) {
      onError((err as Error).message)
    }
  }

  return (
    <>
      <div className="card">
        <div className="card-heading-row">
          <h2>{t('Personen')}</h2>
          <button className="secondary small" onClick={() => setEditing({})}>
            + {t('Neue Person')}
          </button>
        </div>
        <div className="journey-trace-table-scroll">
          <table className="journey-trace-table">
            <thead>
              <tr><th>{t('Partnernummer')}</th><th>{t('Rolle')}</th><th>{t('Versicherungsnummer')}</th><th>{t('KVNR')}</th><th>{t('Name')}</th><th>{t('Geburtsdatum')}</th><th>{t('Adresse')}</th><th>{t('E-Mail-Adresse')}</th><th>{t('Mobilnummer')}</th><th /></tr>
            </thead>
            <tbody>
              {personen.map((p) => (
                <tr key={p.id}>
                  <td><code>{p.id}</code></td>
                  <td>{p.versnr ? t('Versicherter') : t('Partner')}</td>
                  <td>{p.versnr ? <code>{p.versnr}</code> : '–'}</td>
                  <td>{p.kvnr ? <code>{p.kvnr}</code> : '–'}</td>
                  <td>{fullName(p)}</td>
                  <td>{p.geburtsdatum ?? '–'}</td>
                  <td>{address(p)}</td>
                  <td>{p.email ?? '–'}</td>
                  <td>{p.mobilnummer ?? '–'}</td>
                  <td><button className="secondary small" onClick={() => setEditing({ ...p })}>{t('Bearbeiten')}</button></td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </div>

      {editing && (
        <form className="card" onSubmit={save}>
          <h2>{editing.id ? t('{name} bearbeiten', { name: fullName(editing) }) : t('Neue Person')}</h2>
          <div className="form-group">
            <label htmlFor="ext-versnr">{t('Versicherungsnummer (nur wenn bei uns versichert)')}</label>
            <input
              id="ext-versnr"
              inputMode="numeric"
              maxLength={8}
              value={editing.versnr ?? ''}
              placeholder="12345678"
              onChange={(e) => setEditing({ ...editing, versnr: e.target.value })}
            />
          </div>
          <div className="form-group">
            <label htmlFor="ext-kvnr">{t('KVNR (nur mit Versicherungsnummer)')}</label>
            <input
              id="ext-kvnr"
              value={editing.kvnr ?? ''}
              placeholder="A123456789"
              onChange={(e) => setEditing({ ...editing, kvnr: e.target.value })}
            />
          </div>
          {FIELDS.map((f) => (
            <div className="form-group" key={f.key}>
              <label htmlFor={`ext-${f.key}`}>{f.label}</label>
              <input
                id={`ext-${f.key}`}
                type={f.type ?? 'text'}
                value={(editing[f.key] as string | undefined) ?? ''}
                onChange={(e) => setEditing({ ...editing, [f.key]: e.target.value || undefined })}
              />
            </div>
          ))}
          <div className="form-actions">
            <button type="submit">{t('Speichern')}</button>
            <button type="button" className="secondary" onClick={() => setEditing(null)}>{t('Abbrechen')}</button>
          </div>
        </form>
      )}
    </>
  )
}

function FreischaltcodesTab({ personen, onError }: { personen: RegisterPerson[]; onError: (m: string | null) => void }) {
  const [personId, setPersonId] = useState<string | null>(null)
  const [codes, setCodes] = useState<Freischaltcode[]>([])
  const [gueltigBis, setGueltigBis] = useState(inOneYear)
  const [issued, setIssued] = useState<Brief | null>(null)
  const selected = personId ?? personen[0]?.id ?? null

  const reload = useCallback(() => {
    if (selected == null) return
    personenverzeichnisApi.freischaltcodes(selected).then(setCodes).catch((e: Error) => onError(e.message))
  }, [selected, onError])
  useEffect(reload, [reload])

  async function ausstellen() {
    if (selected == null) return
    try {
      setIssued(await personenverzeichnisApi.ausstellen(selected, new Date(`${gueltigBis}T23:59:59`).toISOString()))
      reload()
    } catch (err) {
      onError((err as Error).message)
    }
  }

  async function widerrufen(id: number) {
    try {
      await personenverzeichnisApi.widerrufen(id)
      reload()
    } catch (err) {
      onError((err as Error).message)
    }
  }

  return (
    <div className="card">
      <h2>{t('Freischaltcodes')}</h2>
      <div className="form-group">
        <label htmlFor="ext-person">{t('Person')}</label>
        <select id="ext-person" value={selected ?? ''} onChange={(e) => { setPersonId(e.target.value); setIssued(null) }}>
          {personen.map((p) => <option key={p.id} value={p.id}>{p.kvnr ? `${fullName(p)} (${p.kvnr})` : fullName(p)}</option>)}
        </select>
      </div>

      <div className="journey-trace-table-scroll">
        <table className="journey-trace-table">
          <thead><tr><th>#</th><th>{t('Status')}</th><th>{t('Gültig bis')}</th><th /></tr></thead>
          <tbody>
            {codes.length === 0 && <tr><td colSpan={4}>{t('Keine Freischaltcodes.')}</td></tr>}
            {codes.map((c) => (
              <tr key={c.id}>
                <td>{c.id}</td>
                <td>{c.revokedAt ? t('widerrufen {datum}', { datum: formatDate(c.revokedAt) }) : c.valid ? `✅ ${t('gültig')}` : t('abgelaufen')}</td>
                <td>{formatDate(c.expiresAt)}</td>
                <td>{!c.revokedAt && <button className="secondary small" onClick={() => widerrufen(c.id)}>{t('Widerrufen')}</button>}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="hint">
        {t('Das Personenverzeichnis speichert nur den Hash.')}{' '}
        <Tx text="Den Klartext trägt allein der Brief, er liegt im {briefkasten}." briefkasten={<a href="/briefkasten/" target="identity-demo-briefkasten">{t('Briefkasten')}</a>} />
      </p>

      <h3>{t('Neuen Code ausstellen')}</h3>
      <div className="form-group">
        <label htmlFor="ext-gueltig">{t('Gültig bis')}</label>
        <input id="ext-gueltig" type="date" value={gueltigBis} onChange={(e) => setGueltigBis(e.target.value)} />
      </div>
      <div className="form-actions">
        <button onClick={ausstellen} disabled={selected == null}>
          {t('Ausstellen und Brief versenden')}
        </button>
      </div>
      {issued && <BriefCard brief={issued} person={personen.find((p) => p.id === issued.personId)} />}
    </div>
  )
}

function inOneMonth(): string {
  const d = new Date()
  d.setMonth(d.getMonth() + 1)
  return d.toISOString().slice(0, 10)
}

function einladungStatus(e: Einladung): string {
  if (e.abgeschlossenAm) return t('Vorgang abgeschlossen {datum}', { datum: formatDate(e.abgeschlossenAm) })
  if (e.widerrufenAm) return t('widerrufen {datum}', { datum: formatDate(e.widerrufenAm) })
  return e.offen ? `✅ ${t('offen')}` : t('abgelaufen')
}

/**
 * Invitations to one process by one-time password (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). The
 * register issues them like Freischaltcodes and sends the password by letter; the application only
 * asks it at login (auth-invite). The id is the SHA-256 over person, password and process - the
 * business system ends the invitation with it.
 */
function EinladungenTab({ personen, onError }: { personen: RegisterPerson[]; onError: (m: string | null) => void }) {
  const [personId, setPersonId] = useState<string | null>(null)
  const [einladungen, setEinladungen] = useState<Einladung[]>([])
  const [vorgaenge, setVorgaenge] = useState<Vorgang[]>([])
  const [vorgang, setVorgang] = useState('')
  const [niveau, setNiveau] = useState<'loa1' | 'loa2'>('loa1')
  const [gueltigBis, setGueltigBis] = useState(inOneMonth)
  const [issued, setIssued] = useState<Brief | null>(null)
  const selected = personId ?? personen[0]?.id ?? null
  const gewaehlterVorgang = vorgang || vorgaenge[0]?.id || ''

  useEffect(() => {
    personenverzeichnisApi.vorgaenge().then(setVorgaenge).catch((e: Error) => onError(e.message))
  }, [onError])

  const reload = useCallback(() => {
    if (selected == null) return
    personenverzeichnisApi.einladungen(selected).then(setEinladungen).catch((e: Error) => onError(e.message))
  }, [selected, onError])
  useEffect(reload, [reload])

  const vorgangName = (id: string) => vorgaenge.find((v) => v.id === id)?.name ?? id

  async function run(action: () => Promise<unknown>) {
    try {
      await action()
      reload()
    } catch (err) {
      onError((err as Error).message)
    }
  }

  return (
    <div className="card">
      <h2>{t('Einladungen')}</h2>
      <div className="form-group">
        <label htmlFor="inv-person">{t('Person')}</label>
        <select id="inv-person" value={selected ?? ''} onChange={(e) => { setPersonId(e.target.value); setIssued(null) }}>
          {personen.map((p) => <option key={p.id} value={p.id}>{p.kvnr ? `${fullName(p)} (${p.kvnr})` : fullName(p)}</option>)}
        </select>
      </div>

      <div className="journey-trace-table-scroll">
        <table className="journey-trace-table">
          <thead><tr><th>{t('Vorgang')}</th><th>{t('Niveau')}</th><th>{t('Status')}</th><th>{t('Gültig bis')}</th><th>{t('Id')}</th><th /></tr></thead>
          <tbody>
            {einladungen.length === 0 && <tr><td colSpan={6}>{t('Keine Einladungen.')}</td></tr>}
            {einladungen.map((e) => (
              <tr key={e.id}>
                <td>{vorgangName(e.vorgang)}</td>
                <td>{e.niveau}</td>
                <td>{einladungStatus(e)}</td>
                <td>{formatDate(e.gueltigBis)}</td>
                <td><code title={e.id}>{e.id.slice(0, 12)}…</code></td>
                <td>
                  {e.offen && (
                    <>
                      <button className="secondary small" onClick={() => run(() => personenverzeichnisApi.einladungAbschliessen(e.id))}>{t('Vorgang abschließen')}</button>{' '}
                      <button className="secondary small" onClick={() => run(() => personenverzeichnisApi.einladungWiderrufen(e.id))}>{t('Widerrufen')}</button>
                    </>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
      <p className="hint">
        {t('Das Personenverzeichnis speichert nur den Hash über Person, Einmalkennwort und Vorgang.')}{' '}
        <Tx text="Den Klartext trägt allein der Brief, er liegt im {briefkasten}." briefkasten={<a href="/briefkasten/" target="identity-demo-briefkasten">{t('Briefkasten')}</a>} />
      </p>

      <h3>{t('Neue Einladung ausstellen')}</h3>
      <div className="form-group">
        <label htmlFor="inv-vorgang">{t('Vorgang')}</label>
        <select id="inv-vorgang" value={gewaehlterVorgang} onChange={(e) => setVorgang(e.target.value)}>
          {vorgaenge.map((v) => <option key={v.id} value={v.id}>{v.name}</option>)}
        </select>
      </div>
      <div className="form-group">
        <label htmlFor="inv-niveau">{t('Niveau')}</label>
        <select id="inv-niveau" value={niveau} onChange={(e) => setNiveau(e.target.value as 'loa1' | 'loa2')}>
          <option value="loa1">loa1</option>
          <option value="loa2">loa2</option>
        </select>
      </div>
      <div className="form-group">
        <label htmlFor="inv-gueltig">{t('Gültig bis')}</label>
        <input id="inv-gueltig" type="date" value={gueltigBis} onChange={(e) => setGueltigBis(e.target.value)} />
      </div>
      <div className="form-actions">
        <button
          disabled={selected == null || gewaehlterVorgang === ''}
          onClick={() => run(async () => {
            if (selected == null) return
            setIssued(await personenverzeichnisApi.einladungAusstellen(selected, gewaehlterVorgang, niveau, new Date(`${gueltigBis}T23:59:59`).toISOString()))
          })}
        >
          {t('Ausstellen und Brief versenden')}
        </button>
      </div>
      {issued && <BriefCard brief={issued} person={personen.find((p) => p.id === issued.personId)} vorgangName={issued.vorgang && vorgangName(issued.vorgang)} />}
    </div>
  )
}

function BriefCard({ brief, person, vorgangName }: { brief: Brief; person: RegisterPerson | undefined; vorgangName?: string }) {
  return (
    <div className="brief">
      <div className="brief-meta">{t('An {name} · versandt {datum}', { name: fullName(person), datum: formatDate(brief.versandtAm) })}</div>
      {brief.art === 'EINMALKENNWORT' ? (
        <>
          <div>{t('Für den Vorgang „{vorgang}“ können Sie sich unter /web/ mit Ihrer Versichertennummer und diesem Einmalkennwort anmelden:', { vorgang: vorgangName ?? brief.vorgang ?? '' })}</div>
          <div className="brief-code">{brief.code}</div>
        </>
      ) : (
        <>
          <div>{t('Ihr Freischaltcode lautet:')}</div>
          <div className="brief-code">{brief.code}</div>
        </>
      )}
    </div>
  )
}
