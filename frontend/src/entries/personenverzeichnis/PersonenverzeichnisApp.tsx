import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { ErrorCard } from '../../components/ErrorCard'
import '../../App.css'
import { ChannelNav, type NavTab } from '../../components/ChannelNav'
import { AreaHead, SimBand } from '../../components/SimBand'
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

/** The address in its two lines, leaving out whatever part the register does not have. */
const street = (p: RegisterPerson) => [p.strasse, p.hausnummer].filter(Boolean).join(' ')
const city = (p: RegisterPerson) => [p.plz, p.ort].filter(Boolean).join(' ')

/** A day in the reader's language - from a date (yyyy-mm-dd) or an instant. */
function formatDay(value: string): string {
  return new Date(value.length === 10 ? `${value}T00:00:00` : value).toLocaleDateString(language(), { dateStyle: 'medium' })
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
    <div className="web-shell channel-pv">
      <ChannelNav area="pv" tabs={TABS} sub={tab} onSelectTab={setTab} />
      <SimBand label={t('Simuliert · Fremdes System')}>
        <Tx
          text="Unsere Anwendung liest es nur über {schnittstelle} und fragt es beim Freischaltcode ({fsc}) und bei der Zuordnung ({zuordnung})."
          schnittstelle={<code>PersonDirectory</code>}
          fsc={<code>ident-fsc</code>}
          zuordnung={<code>ident-kvnr</code>}
        />{' '}
        {t('Was Sie hier ändern, meldet es an die Konten - und über sie an Keycloak.')}
      </SimBand>
      <main className="area-page">
        {error && <ErrorCard title={t('Fehler')}>{error}</ErrorCard>}
        {tab === 'personen' && <PersonenTab personen={personen} onChanged={reload} onError={setError} />}
        {tab === 'freischaltcodes' && <FreischaltcodesTab personen={personen} onError={setError} />}
        {tab === 'einladungen' && <EinladungenTab personen={personen} onError={setError} />}
      </main>
    </div>
  )
}

/** Whether a person matches the search: name, Partnernummer, insurance number, KVNR or e-mail. */
function matches(p: RegisterPerson, query: string): boolean {
  const q = query.trim().toLowerCase()
  if (!q) return true
  return [fullName(p), p.id, p.versnr, p.kvnr, p.email].some((v) => v?.toLowerCase().includes(q))
}

function RoleTag({ person }: { person: RegisterPerson }) {
  return person.versnr ? <span className="role-tag">{t('Versicherter')}</span> : <span className="role-tag role-tag--partner">{t('Partner')}</span>
}

/** The register's choice of person, the same on both issuing tabs. */
function PersonSelect({ id, personen, value, onChange }: { id: string; personen: RegisterPerson[]; value: string | null; onChange: (id: string) => void }) {
  return (
    <div className="form-group">
      <label htmlFor={id}>{t('Person')}</label>
      <select id={id} value={value ?? ''} onChange={(e) => onChange(e.target.value)}>
        {personen.map((p) => <option key={p.id} value={p.id}>{p.kvnr ? `${fullName(p)} (${p.kvnr})` : fullName(p)}</option>)}
      </select>
    </div>
  )
}

function StatusTag({ kind, children }: { kind: 'ok' | 'off'; children: string }) {
  return <span className={kind === 'ok' ? 'status-tag status-tag--ok' : 'status-tag'}>{children}</span>
}

function PersonenTab({ personen, onChanged, onError }: { personen: RegisterPerson[]; onChanged: () => void; onError: (m: string | null) => void }) {
  // null = no form open; an object without id = new person.
  const [editing, setEditing] = useState<RegisterPerson | null>(null)
  const [query, setQuery] = useState('')
  const shown = personen.filter((p) => matches(p, query))
  const form = useRef<HTMLFormElement>(null)

  // The form opens under the table; bring it into view, a long list would hide it.
  const editingId = editing ? (editing.id ?? '') : null
  useEffect(() => {
    if (editingId !== null) form.current?.scrollIntoView?.({ block: 'nearest', behavior: 'smooth' })
  }, [editingId])

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
      <AreaHead kicker={t('Personenverzeichnis')} title={t('Personen')} />
      <div className="area-toolbar">
        <label className="area-search">
          <svg width="18" height="18" viewBox="0 0 18 18" fill="none" aria-hidden="true">
            <circle cx="8" cy="8" r="5.5" stroke="currentColor" strokeWidth="1.6" />
            <path d="M12.5 12.5L16 16" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
          </svg>
          <input type="search" value={query} onChange={(e) => setQuery(e.target.value)} placeholder={t('Name, Partnernummer oder KVNR')} aria-label={t('Personen suchen')} />
        </label>
        <button onClick={() => setEditing({})}>{t('Neue Person')}</button>
      </div>
      <div className="data-card">
        <table className="data-table">
          <thead>
            <tr><th>{t('Person')}</th><th>{t('Rolle')}</th><th>{t('Partnernummer')}</th><th>{t('KVNR')}</th><th>{t('Geburtsdatum')}</th><th>{t('Adresse')}</th><th /></tr>
          </thead>
          <tbody>
            {shown.length === 0 && <tr><td colSpan={7} className="data-empty">{t('Keine Person gefunden.')}</td></tr>}
            {shown.map((p) => (
              <tr key={p.id}>
                <td>
                  <strong>{fullName(p)}</strong>
                  <span className="data-sub">{p.email ?? '–'}</span>
                  {p.mobilnummer && <span className="data-sub">{p.mobilnummer}</span>}
                </td>
                <td>
                  <RoleTag person={p} />
                  {p.versnr && <span className="data-sub data-mono" title={t('Versicherungsnummer')}>{p.versnr}</span>}
                </td>
                <td className="data-mono">{p.id}</td>
                <td className="data-mono">{p.kvnr ?? '–'}</td>
                <td>{p.geburtsdatum ? formatDay(p.geburtsdatum) : '–'}</td>
                <td className="data-muted data-nowrap">
                  {street(p) || '–'}
                  {city(p) && <span className="data-sub">{city(p)}</span>}
                </td>
                <td className="data-action"><button className="secondary small" onClick={() => setEditing({ ...p })}>{t('Bearbeiten')}</button></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {editing && (
        <form className="card pv-form" onSubmit={save} ref={form}>
          <h2>{editing.id ? t('{name} bearbeiten', { name: fullName(editing) }) : t('Neue Person')}</h2>
          <div className="pv-form-grid">
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
          </div>
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
    <>
      <AreaHead kicker={t('Personenverzeichnis')} title={t('Freischaltcodes')} />
      <div className="pv-split">
        <section className="card pv-stock">
          <PersonSelect id="ext-person" personen={personen} value={selected} onChange={(id) => { setPersonId(id); setIssued(null) }} />
          <table className="data-table">
            <thead><tr><th>#</th><th>{t('Status')}</th><th>{t('Gültig bis')}</th><th /></tr></thead>
            <tbody>
              {codes.length === 0 && <tr><td colSpan={4} className="data-empty">{t('Keine Freischaltcodes.')}</td></tr>}
              {codes.map((c) => (
                <tr key={c.id}>
                  <td className="data-mono">{c.id}</td>
                  <td>
                    {c.revokedAt ? (
                      <>
                        <StatusTag kind="off">{t('widerrufen')}</StatusTag>
                        <span className="data-sub data-nowrap">{formatDate(c.revokedAt)}</span>
                      </>
                    ) : c.valid ? (
                      <StatusTag kind="ok">{t('gültig')}</StatusTag>
                    ) : (
                      <StatusTag kind="off">{t('abgelaufen')}</StatusTag>
                    )}
                  </td>
                  <td className="data-nowrap">{formatDay(c.expiresAt)}</td>
                  <td className="data-action">{!c.revokedAt && <button className="secondary small" onClick={() => widerrufen(c.id)}>{t('Widerrufen')}</button>}</td>
                </tr>
              ))}
            </tbody>
          </table>
          <p className="pv-note">
            {t('Das Personenverzeichnis speichert nur den Hash.')}{' '}
            <Tx text="Den Klartext trägt allein der Brief, er liegt im {briefkasten}." briefkasten={<a href="/briefkasten/" target="identity-demo-briefkasten">{t('Briefkasten')}</a>} />
          </p>
        </section>

        <section className="card pv-issue">
          <h2>{t('Neuen Code ausstellen')}</h2>
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
        </section>
      </div>
    </>
  )
}

function inOneMonth(): string {
  const d = new Date()
  d.setMonth(d.getMonth() + 1)
  return d.toISOString().slice(0, 10)
}

/** What became of an invitation: a short state for the tag, and when, if it ended. */
function einladungStatus(e: Einladung): { label: string; kind: 'ok' | 'off'; at?: string } {
  if (e.abgeschlossenAm) return { label: t('abgeschlossen'), kind: 'off', at: e.abgeschlossenAm }
  if (e.widerrufenAm) return { label: t('widerrufen'), kind: 'off', at: e.widerrufenAm }
  return e.offen ? { label: t('offen'), kind: 'ok' } : { label: t('abgelaufen'), kind: 'off' }
}

/**
 * Invitations to one process by one-time password (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). The
 * register issues them like Freischaltcodes and sends the password by letter; the application only
 * asks it at login (auth-invite-lookup). The id is the SHA-256 over person, password and process - the
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
    <>
      <AreaHead kicker={t('Personenverzeichnis')} title={t('Einladungen')} />
      <div className="pv-split">
        <section className="card pv-stock">
          <PersonSelect id="inv-person" personen={personen} value={selected} onChange={(id) => { setPersonId(id); setIssued(null) }} />
          <div className="data-scroll">
            <table className="data-table">
              <thead><tr><th>{t('Vorgang')}</th><th>{t('Niveau')}</th><th>{t('Status')}</th><th>{t('Gültig bis')}</th><th /></tr></thead>
              <tbody>
                {einladungen.length === 0 && <tr><td colSpan={5} className="data-empty">{t('Keine Einladungen.')}</td></tr>}
                {einladungen.map((e) => (
                  <tr key={e.id}>
                    <td>
                      <strong>{vorgangName(e.vorgang)}</strong>
                      <span className="data-sub data-mono" title={e.id}>{e.id.slice(0, 12)}…</span>
                    </td>
                    <td className="data-mono">{e.niveau}</td>
                    <td>
                      <StatusTag kind={einladungStatus(e).kind}>{einladungStatus(e).label}</StatusTag>
                      {einladungStatus(e).at && <span className="data-sub data-nowrap">{formatDate(einladungStatus(e).at)}</span>}
                    </td>
                    <td className="data-nowrap">{formatDay(e.gueltigBis)}</td>
                    <td className="data-action">
                      {e.offen && (
                        <span className="data-buttons">
                          <button className="secondary small" onClick={() => run(() => personenverzeichnisApi.einladungAbschliessen(e.id))}>{t('Vorgang abschließen')}</button>
                          <button className="secondary small" onClick={() => run(() => personenverzeichnisApi.einladungWiderrufen(e.id))}>{t('Widerrufen')}</button>
                        </span>
                      )}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
          <p className="pv-note">
            {t('Das Personenverzeichnis speichert nur den Hash über Person, Einmalkennwort und Vorgang.')}{' '}
            <Tx text="Den Klartext trägt allein der Brief, er liegt im {briefkasten}." briefkasten={<a href="/briefkasten/" target="identity-demo-briefkasten">{t('Briefkasten')}</a>} />
          </p>
        </section>

        <section className="card pv-issue">
          <h2>{t('Neue Einladung ausstellen')}</h2>
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
        </section>
      </div>
    </>
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
