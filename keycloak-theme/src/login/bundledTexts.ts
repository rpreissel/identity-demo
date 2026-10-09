import de from '../../messages/messages_de.properties?raw'
import en from '../../messages/messages_en.properties?raw'
import { parseProperties } from '../texts'

const BUNDLES: Record<string, string> = { de, en }

/**
 * The theme's own wordings (keycloak-theme/messages) in one language. The extension sends a page
 * its `texts`; Keycloak's own pages (KcTemplate.tsx) get none, so they take them from here, in
 * the login's language - German when there is no bundle for it.
 */
export function bundledTexts(language: string | undefined): Record<string, string> {
  return parseProperties(BUNDLES[language ?? 'de'] ?? de)
}
