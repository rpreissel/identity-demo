import { toolLabel } from './journeyTraceText'
import { t } from './texts'
import { enrollmentToolFor, metaFor } from './tools/registry'
import type { AccountMatch, AccountMatchKind, AccountRole } from './types'

/**
 * Reads an account for the operator (admin page, AdminAccountView, and the account search of the
 * journey trace): what a lookup matched, the role, and the raw names the account module stores.
 */

export function matchKindLabel(kind: AccountMatchKind): string {
  switch (kind) {
    case 'EMAIL':
      return t('E-Mail-Adresse')
    case 'KVNR':
      return t('KVNR')
    case 'PARTNER_NUMBER':
      return t('Partnernummer')
    case 'MEMBER_NUMBER':
      return t('Versicherungsnummer')
    case 'ACCOUNT_NUMBER':
      return t('Kontonummer')
  }
}

export function accountName(account: { accountId: number; displayName?: string | null }): string {
  return account.displayName ?? t('Konto {id}', { id: account.accountId })
}

/** What one search found, as a line: name and what it matched. */
export function matchLabel(match: AccountMatch): string {
  return t('{name}, gefunden über {merkmal}', { name: accountName(match), merkmal: matchKindLabel(match.matchedBy) })
}

export function roleLabel(role: AccountRole): string {
  switch (role) {
    case 'INSURED':
      return t('Versicherter')
    case 'PARTNER':
      return t('Partner')
    case 'PROSPECT':
      return t('Interessent')
  }
}

export function roleExplanation(role: AccountRole): string {
  switch (role) {
    case 'INSURED':
      return t('Bei uns versichert, mit Versicherungsnummer.')
    case 'PARTNER':
      return t('Uns als Person bekannt, mit Partnernummer, aber nicht bei uns versichert.')
    case 'PROSPECT':
      return t('Keiner Person zugeordnet.')
  }
}

/** The wire name of an attribute type (`family_name`) as people read it. */
export function attributeLabel(type: string): string {
  switch (type) {
    case 'person_id':
      return t('Partnernummer')
    case 'member_number':
      return t('Versicherungsnummer')
    case 'email':
      return t('E-Mail-Adresse')
    case 'kvnr':
      return t('KVNR')
    case 'family_name':
      return t('Familienname')
    case 'given_names':
      return t('Vornamen')
    case 'birth_date':
      return t('Geburtsdatum')
    case 'street_address':
      return t('Straße und Hausnummer')
    case 'postal_code':
      return t('Postleitzahl')
    case 'locality':
      return t('Ort')
    default:
      return type
  }
}

/** Where a claim came from: the register, the person alone, or the tool that proved it. */
export function claimSourceLabel(source: string | null | undefined): string {
  if (!source) return '–'
  if (source === 'person_directory') return t('Personenverzeichnis')
  if (source === 'self-reported') return t('selbst angegeben')
  return toolLabel(source)
}

/** Who withdrew a value (RetractionSource). */
export function retractionSourceLabel(source: string): string {
  switch (source) {
    case 'ACCOUNT_MANAGEMENT':
      return t('Kontoverwaltung, z. B. ein Verfahren wurde entfernt')
    case 'ACCOUNT_HOLDER':
      return t('die Person selbst')
    case 'PERSON_DIRECTORY':
      return t('Personenverzeichnis')
    case 'OPERATOR':
      return t('Betrieb')
    default:
      return source
  }
}

/** An account method (`sms`) by the name of the tool that sets it up. */
export function methodLabel(method: string): string {
  return metaFor(enrollmentToolFor(method) ?? method).label
}

export function changeTypeLabel(type: string): string {
  switch (type) {
    case 'IDENTIFIED':
      return t('Identifiziert')
    case 'ATTRIBUTE_RETRACTED':
      return t('Angabe zurückgezogen')
    case 'METHOD_ADDED':
      return t('Verfahren eingerichtet')
    case 'METHOD_DEACTIVATED':
      return t('Verfahren entfernt')
    case 'ACCOUNT_DELETED':
      return t('Konto gelöscht')
    case 'ACCOUNT_ABSORBED':
      return t('In ein anderes Konto übernommen')
    default:
      return type
  }
}

export function signInTypeLabel(type: string): string {
  switch (type) {
    case 'SIGNED_IN':
      return t('Angemeldet')
    case 'STEPPED_UP':
      return t('Sicherheitsniveau erhöht')
    case 'SIGN_IN_FAILED':
      return t('Anmeldung fehlgeschlagen')
    case 'LOCKED_OUT':
      return t('Gesperrt')
    case 'SIGNED_OUT':
      return t('Abgemeldet')
    default:
      return type
  }
}

/** A log entry's details as one line, without what the columns already show. */
export function logDetailLine(details: Record<string, unknown>): string {
  return Object.entries(details)
    .filter(([, value]) => value !== null && value !== undefined && value !== '' && !(Array.isArray(value) && value.length === 0))
    .map(([key, value]) => `${key}: ${Array.isArray(value) ? value.join(', ') : typeof value === 'object' ? JSON.stringify(value) : String(value)}`)
    .join(' · ')
}

