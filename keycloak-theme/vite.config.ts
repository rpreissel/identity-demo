import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { keycloakify } from 'keycloakify/vite-plugin'
import { fileURLToPath } from 'node:url'
import { themeProperties } from './scripts/texts-per-page.mjs'
import { appendMessages } from './scripts/append-messages.mjs'

/** The orchestrator's own texts, written by /translate-texts (docs/adr/ADR-033-texte-als-vorlage-im-code.md). */
const messagesDir = fileURLToPath(new URL('./messages', import.meta.url))

const THEME_NAME = 'orchestrator-keycloakify'

export default defineConfig({
  plugins: [
    react(),
    keycloakify({
      themeName: THEME_NAME,
      accountThemeImplementation: 'none',
      // Keycloak 26 only - one jar, named for the image.
      keycloakVersionTargets: { '22-to-25': false, 'all-other-versions': `${THEME_NAME}-theme.jar` },
      // orchestratorTexts.<pageId> names the texts each React page uses; the extension sends a
      // page only those (kcContext.texts, docs/adr/ADR-057-keycloakify-einziges-login-theme.md).
      extraThemeProperties: themeProperties(fileURLToPath(new URL('./src', import.meta.url))),
      // The orchestrator's texts go into the theme's own messages bundles, where the extension's
      // KcTexts reads them (theme.getEnhancedMessages). Runs in the jar's resources directory.
      postBuild: async () => appendMessages(messagesDir, 'theme/orchestrator-keycloakify/login/messages'),
    }),
  ],
})

