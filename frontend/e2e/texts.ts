import { readFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { textId } from '../src/texts'

/**
 * The German wording the app shows for a template (docs/adr/ADR-033). The suite names texts by their
 * source template, like the unit tests do; the browser runs in German (playwright.config.ts), so
 * what it shows is the de bundle's rewording - looked up here by the app's own `textId`, not a copy of it.
 */
function load(name: string): Map<string, string> {
  const file = join(dirname(fileURLToPath(import.meta.url)), `../../src/main/resources/texts/${name}/texts_de.properties`)
  const wordings = new Map<string, string>()
  for (const line of readFileSync(file, 'utf8').split('\n')) {
    if (!line || line.startsWith('#')) continue
    const at = line.indexOf('=')
    wordings.set(line.slice(0, at), line.slice(at + 1).replace(/\\(.)/g, '$1'))
  }
  return wordings
}

const bundle = load('app')
const registerBundle = load('personenverzeichnis')

export function ui(template: string): string {
  return bundle.get(textId(template)) ?? template
}

/** The same for the simulated Personenverzeichnis, which has its own bundle. */
export function pv(template: string): string {
  return registerBundle.get(textId(template)) ?? template
}

/** The welcome heading of a logged-in user: its name part varies, so only the words before it. */
export const welcomeHeading = new RegExp(`^${ui('Willkommen, {name}!').split('{name}')[0]}`)

/** For matching inside a larger text or a regex. */
export function uiPattern(...templates: string[]): RegExp {
  return new RegExp(templates.map((t) => ui(t).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')).join('|'))
}
