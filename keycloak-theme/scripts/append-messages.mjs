// Appends the orchestrator's own texts (messages/messages_<lang>.properties, written by
// /translate-texts) to the messages bundles Keycloakify writes into the theme jar - so the
// extension's KcTexts finds them in the login theme (docs/adr/ADR-057-keycloakify-einziges-login-theme.md).
// Keycloakify's bundles are ASCII with \uXXXX escapes; ours are UTF-8 and get escaped the same way.
import { appendFileSync, existsSync, readdirSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

/** `ä` -> `ä`: Properties reads the escape whatever charset Keycloak assumes. */
export function escapeNonAscii(text) {
  return text.replace(/[^\x00-\x7f]/g, (c) => '\\u' + c.charCodeAt(0).toString(16).padStart(4, '0'))
}

export function appendMessages(sourceDir, targetDir) {
  for (const name of readdirSync(sourceDir).filter((n) => /^messages_[\w-]+\.properties$/.test(n))) {
    const target = join(targetDir, name)
    // A language Keycloakify ships no bundle for would be a language the theme does not offer.
    if (!existsSync(target)) throw new Error(`${target} missing - the theme has no bundle for ${name}`)
    appendFileSync(target, '\n' + escapeNonAscii(readFileSync(join(sourceDir, name), 'utf8')))
  }
}
