import fs from 'node:fs'
import path from 'node:path'
import type MarkdownIt from 'markdown-it'
import { pageTitle, tocLabels } from './sidebar'

// Auf GitHub nennen Links oft nur die Datei ([01-ueberblick.md](01-ueberblick.md)); im Buch
// steht dort der Titel der Seite (kurzer Titel aus den Verzeichnissen, sonst die Ueberschrift).
// Listenpunkte, die mit einem Link auf eine nicht ins Buch uebernommene Datei beginnen, fallen
// weg. Muss vor github-links laufen (sieht die Original-Links).

type Options = { docsDir: string; excluded: string[] }

export function linkTitles(md: MarkdownIt, opts: Options) {
  md.core.ruler.push('link-titles', (state) => {
    const source: string | undefined = state.env.realPath ?? state.env.path
    if (!source) return
    const labels = tocLabels(opts.docsDir)
    dropExcludedListItems(state.tokens, source, opts)
    for (const token of state.tokens) {
      const children = token.children ?? []
      for (let i = 0; i + 1 < children.length; i++) {
        if (children[i].type !== 'link_open') continue
        const href = children[i].attrGet('href') ?? ''
        const text = children[i + 1]
        if (!['text', 'code_inline'].includes(text.type) || children[i + 2]?.type !== 'link_close') continue
        const target = href.replace(/#.*$/, '')
        if (text.content !== target && text.content !== path.basename(target) && text.content !== target.replace(/\/$/, '')) continue
        const title = titleOf(target, source, opts, labels)
        if (!title) continue
        text.type = 'text'
        text.content = title
      }
    }
  })
}

function resolveInBook(target: string, source: string, opts: Options): string | undefined {
  if (!target || /^([a-z][a-z0-9+.-]*:|\/|#)/i.test(target)) return undefined
  let abs = path.resolve(path.dirname(source), decodeURI(target))
  if (fs.existsSync(abs) && fs.statSync(abs).isDirectory()) abs = path.join(abs, 'README.md')
  if (!abs.endsWith('.md') || !abs.startsWith(opts.docsDir + path.sep) || !fs.existsSync(abs)) return undefined
  return abs
}

function titleOf(target: string, source: string, opts: Options, labels: Map<string, string>): string | undefined {
  const abs = resolveInBook(target, source, opts)
  if (!abs || isExcluded(abs, opts)) return undefined
  return pageTitle(opts.docsDir, path.relative(opts.docsDir, abs).split(path.sep).join('/'), labels)
}

function isExcluded(abs: string, opts: Options): boolean {
  return opts.excluded.includes(path.relative(opts.docsDir, abs).split(path.sep).join('/'))
}

function dropExcludedListItems(tokens: any[], source: string, opts: Options) {
  for (let i = 0; i < tokens.length; i++) {
    if (tokens[i].type !== 'list_item_open') continue
    const inline = tokens.slice(i + 1, i + 4).find((t) => t.type === 'inline')
    const first = (inline?.children ?? []).find(
      (t: any) => !['strong_open', 'em_open'].includes(t.type) && !(t.type === 'text' && !t.content),
    )
    if (first?.type !== 'link_open') continue
    const abs = resolveInBook((first.attrGet('href') ?? '').replace(/#.*$/, ''), source, opts)
    if (!abs || !isExcluded(abs, opts)) continue
    let depth = 0
    let end = i
    for (; end < tokens.length; end++) {
      if (tokens[end].type === 'list_item_open') depth++
      if (tokens[end].type === 'list_item_close' && --depth === 0) break
    }
    tokens.splice(i, end - i + 1)
    i--
  }
}
