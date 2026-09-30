import fs from 'node:fs'
import path from 'node:path'
import type MarkdownIt from 'markdown-it'

// Die Doku verlinkt relativ, damit sie auf GitHub funktioniert. Auf der Website gibt es nur
// docs/; Links, die aus docs/ herausfuehren (../../src/...kt, ../AGENTS.md), zeigen deshalb auf
// die Datei im Repository. Links auf README.md werden zum Ordner, weil README.md als Startseite
// des Ordners (index) ausgeliefert wird.

const EXTERNAL = /^([a-z][a-z0-9+.-]*:|\/\/)/i

// excluded: Dateien in docs/, die nicht ins Buch gehoeren (relativ zu docs/); sie werden wie
// Dateien ausserhalb von docs/ behandelt.
type Options = { docsDir: string; repoDir: string; repoUrl: string; excluded: string[] }

export function githubLinks(md: MarkdownIt, opts: Options) {
  md.core.ruler.push('github-links', (state) => {
    const source: string | undefined = state.env.realPath ?? state.env.path
    if (!source) return
    const walk = (tokens: typeof state.tokens) => {
      for (const token of tokens) {
        if (token.children) walk(token.children)
        if (token.type !== 'link_open') continue
        const href = token.attrGet('href')
        if (!href || href.startsWith('#') || href.startsWith('/') || EXTERNAL.test(href)) continue
        token.attrSet('href', rewrite(href, source, opts))
      }
    }
    walk(state.tokens)
  })
}

function rewrite(href: string, source: string, opts: Options): string {
  const hashAt = href.indexOf('#')
  const target = hashAt >= 0 ? href.slice(0, hashAt) : href
  const hash = hashAt >= 0 ? href.slice(hashAt) : ''
  const abs = path.resolve(path.dirname(source), decodeURI(target))

  const inDocs = abs === opts.docsDir || abs.startsWith(opts.docsDir + path.sep)
  const inBook = inDocs && !opts.excluded.includes(path.relative(opts.docsDir, abs).split(path.sep).join('/'))
  if (!inBook) {
    const rel = path.relative(opts.repoDir, abs).split(path.sep).join('/')
    const isDir = target.endsWith('/') || (fs.existsSync(abs) && fs.statSync(abs).isDirectory())
    return `${opts.repoUrl}/${isDir ? 'tree' : 'blob'}/main/${rel}${hash}`
  }
  if (path.basename(target) === 'README.md') {
    const dir = target.slice(0, -'README.md'.length)
    return (dir || './') + hash
  }
  return href
}
