import { describe, expect, it } from 'vitest'
import { mkdtempSync, readFileSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { appendMessages, escapeNonAscii } from './append-messages.mjs'

describe('append messages', () => {
  it('escapes what is not ASCII, as Keycloakify writes its bundles', () => {
    expect(escapeNonAscii('Zurück zur Übersicht')).toBe('Zur\\u00fcck zur \\u00dcbersicht')
  })

  it('appends each language to the theme bundle of the same name', () => {
    const source = mkdtempSync(join(tmpdir(), 'messages-'))
    const target = mkdtempSync(join(tmpdir(), 'theme-'))
    writeFileSync(join(source, 'messages_de.properties'), 'zurueck-548611=Zurück\n')
    writeFileSync(join(target, 'messages_de.properties'), 'doBack=Zur\\u00fcck\n')

    appendMessages(source, target)

    expect(readFileSync(join(target, 'messages_de.properties'), 'utf8')).toBe('doBack=Zur\\u00fcck\n\nzurueck-548611=Zur\\u00fcck\n')
  })

  it('refuses a language the theme has no bundle for', () => {
    const source = mkdtempSync(join(tmpdir(), 'messages-'))
    writeFileSync(join(source, 'messages_xx.properties'), 'a=b\n')
    expect(() => appendMessages(source, mkdtempSync(join(tmpdir(), 'theme-')))).toThrow(/no bundle/)
  })
})
