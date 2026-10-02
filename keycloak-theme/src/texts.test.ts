import { afterEach, describe, expect, it } from 'vitest'
import { setTexts, t, textId } from './texts'

describe('texts', () => {
  afterEach(() => setTexts(undefined))

  // The shared samples - the same list pins Text.idOf (TextIdTest), KcText.idOf (KcTextsTest) and
  // the frontend's textId (frontend/src/texts.test.tsx).
  it.each([
    ['Account not found', 'account-not-found-08a2ef'],
    ['Journey-Trace laden fehlgeschlagen', 'journey-trace-laden-fehlgeschlagen-cf9829'],
    ['Noch {anzahl} Versuche', 'noch-anzahl-versuche-fb9887'],
    ['Größe über Maß – ÄÖÜ äöü ß', 'groesse-ueber-mass-aeoeue-aeoeue-ss-30656e'],
    ['Löscht alle Konten (samt Geräten, Verfahren und Journey-Trace), setzt alles zurück', 'loescht-alle-konten-samt-geraeten-038056'],
    ['Café', 'caf-73473d'],
    ['!!!', 'e84c53'],
    // messages_de.properties: "# Quelle: Weiter" / weiter-1e14bd=Weiter
    ['Weiter', 'weiter-1e14bd'],
  ])('computes the same id as the extension for %s', (template, id) => {
    expect(textId(template)).toBe(id)
  })

  it('shows the wording Keycloak rendered into the page', () => {
    setTexts({ [textId('Weiter')]: 'Continue' })

    expect(t('Weiter')).toBe('Continue')
  })

  it('fills placeholders into the wording', () => {
    setTexts({ [textId('Demo-Code: {wert}')]: 'Demo code: {wert}' })

    expect(t('Demo-Code: {wert}', { wert: '123456' })).toBe('Demo code: 123456')
  })

  it('shows the template itself without a wording', () => {
    setTexts(undefined)

    expect(t('Weiter')).toBe('Weiter')
  })
})
