import { mockApi } from './mockApi'
import { PERSONENVERZEICHNIS_TEXTS } from './texts'

/**
 * The simulated person register's own API (`/mock-personenverzeichnis`, ADR-31) - not this application's
 * contract, so no generated types and no DPoP: the register knows nothing about our channels.
 */
export interface RegisterPerson {
  /** The Partnernummer, handed out by the register (ADR-34). */
  id?: string
  kvnr?: string
  /** Versicherungsnummer - eight digits, only for a person insured with us. */
  versnr?: string
  name?: string
  vorname?: string
  geburtsdatum?: string
  strasse?: string
  hausnummer?: string
  plz?: string
  ort?: string
  email?: string
  mobilnummer?: string
}

export interface Freischaltcode {
  id: number
  personId: string
  expiresAt: string
  revokedAt?: string
  valid: boolean
}

export interface Brief {
  id: number
  personId: string
  code: string
  versandtAm: string
  /** What the letter carries (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). */
  art: 'FREISCHALTCODE' | 'EINMALKENNWORT'
  freischaltcodeId?: number
  /** Only for a one-time password: the invitation and its process. */
  einladungId?: string
  vorgang?: string
}

/** A process the register invites to. */
export interface Vorgang {
  id: string
  name: string
}

/** An invitation to one process - its id is the SHA-256 over person, one-time password and process. */
export interface Einladung {
  id: string
  personId: string
  vorgang: string
  niveau: 'loa1' | 'loa2'
  gueltigBis: string
  ausgestelltAm: string
  abgeschlossenAm?: string
  widerrufenAm?: string
  offen: boolean
}

const call = mockApi('/mock-personenverzeichnis', PERSONENVERZEICHNIS_TEXTS)

export const personenverzeichnisApi = {
  personen: () => call<RegisterPerson[]>('GET', '/personen'),
  anlegen: (person: RegisterPerson) => call<RegisterPerson>('POST', '/personen', person),
  aendern: (id: string, person: RegisterPerson) => call<RegisterPerson>('PUT', `/personen/${id}`, person),
  freischaltcodes: (personId: string) => call<Freischaltcode[]>('GET', `/personen/${personId}/freischaltcodes`),
  ausstellen: (personId: string, gueltigBis: string) =>
    call<Brief>('POST', `/personen/${personId}/freischaltcodes`, { gueltigBis }),
  widerrufen: (freischaltcodeId: number) => call<void>('DELETE', `/freischaltcodes/${freischaltcodeId}`),
  briefe: () => call<Brief[]>('GET', '/briefe'),
  vorgaenge: () => call<Vorgang[]>('GET', '/vorgaenge'),
  einladungen: (personId: string) => call<Einladung[]>('GET', `/personen/${personId}/einladungen`),
  einladungAusstellen: (personId: string, vorgang: string, niveau: string, gueltigBis: string) =>
    call<Brief>('POST', `/personen/${personId}/einladungen`, { vorgang, niveau, gueltigBis }),
  /** Stands in for the business system that ends the process (docs/adr/ADR-048-vorgangszugang-mit-einmalkennwort.md). */
  einladungAbschliessen: (einladungId: string) => call<Einladung>('POST', `/einladungen/${einladungId}/abschluss`),
  einladungWiderrufen: (einladungId: string) => call<void>('DELETE', `/einladungen/${einladungId}`),
}
