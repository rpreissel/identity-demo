import fs from 'node:fs'
import { createRequire } from 'node:module'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig } from 'vitepress'
import { slug } from 'github-slugger'
import { sidebarFromReadme } from './plugins/sidebar'
import { githubLinks } from './plugins/github-links'
import { linkTitles } from './plugins/link-titles'
import { videoLinks } from './plugins/video'
import { mermaidFences } from './plugins/mermaid'

// Die Website liest docs/ unveraendert: keine Front Matter, keine umbenannten Dateien, relative
// .md-Links wie auf GitHub. Alles, was nur die Website braucht, steht hier in site/.

const require = createRequire(import.meta.url)
const repoDir = path.resolve(fileURLToPath(new URL('.', import.meta.url)), '../..')
const docsDir = path.join(repoDir, 'docs')
const repoUrl = 'https://github.com/rpreissel/identity-demo'
// Dateien, die in docs/ bleiben, aber nicht ins Buch gehoeren; Links darauf zeigen auf GitHub.
const excluded = ['00-agent-quickstart.md']

export default defineConfig({
  srcDir: '../docs',
  srcExclude: excluded,
  base: '/identity-demo/',
  lang: 'de-DE',
  title: 'Identity-Demo',
  description: 'Anmeldung und Identifizierung für App und Website: Dokumentation',
  lastUpdated: true,
  // Tote Links brechen den Build; nur Adressen der laufenden Demo (localhost) sind erlaubt.
  ignoreDeadLinks: 'localhostLinks',

  // README.md ist auf GitHub die Startseite eines Ordners, auf der Website index.
  rewrites: {
    'README.md': 'index.md',
    ':dir/README.md': ':dir/index.md',
  },

  markdown: {
    // Anker wie auf GitHub, damit Links wie 08-projektrahmen.md#fachkern-und-technik passen.
    anchor: { slugify: slug },
    headers: { slugify: slug },
    config(md) {
      linkTitles(md, { docsDir, excluded })
      githubLinks(md, { docsDir, repoDir, repoUrl, excluded })
      videoLinks(md)
      mermaidFences(md)
    },
  },

  // Die Seiten liegen ausserhalb von site/; von dort aus faende Vite site/node_modules nicht.
  vite: {
    resolve: {
      alias: [{ find: /^vue(\/.*)?$/, replacement: path.dirname(require.resolve('vue/package.json')) + '$1' }],
    },
  },

  // Videos und andere Medien werden nicht gebuendelt, sondern unter ihrem Pfad kopiert; so
  // bleiben die relativen Links aus docs/ gueltig.
  buildEnd({ outDir }) {
    fs.cpSync(path.join(docsDir, 'media'), path.join(outDir, 'media'), { recursive: true })
  },

  themeConfig: {
    sidebar: sidebarFromReadme(docsDir, excluded),
    outline: { level: [2, 3], label: 'Auf dieser Seite' },
    docFooter: { prev: 'Vorherige Seite', next: 'Nächste Seite' },
    lastUpdated: { text: 'Zuletzt geändert' },
    editLink: {
      pattern: `${repoUrl}/blob/main/docs/:path`,
      text: 'Auf GitHub ansehen',
    },
    socialLinks: [{ icon: 'github', link: repoUrl }],
    returnToTopLabel: 'Nach oben',
    sidebarMenuLabel: 'Inhalt',
    darkModeSwitchLabel: 'Darstellung',
    lightModeSwitchTitle: 'Helle Darstellung',
    darkModeSwitchTitle: 'Dunkle Darstellung',
    notFound: { title: 'Seite nicht gefunden', quote: '', linkText: 'Zur Übersicht' },
    search: {
      provider: 'local',
      options: {
        translations: {
          button: { buttonText: 'Suchen', buttonAriaLabel: 'Suchen' },
          modal: {
            displayDetails: 'Details anzeigen',
            resetButtonTitle: 'Suche zurücksetzen',
            backButtonTitle: 'Suche schließen',
            noResultsText: 'Keine Treffer für',
            footer: { selectText: 'öffnen', navigateText: 'wechseln', closeText: 'schließen' },
          },
        },
      },
    },
  },
})
