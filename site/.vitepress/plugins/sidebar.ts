import fs from 'node:fs'
import path from 'node:path'
import type { DefaultTheme } from 'vitepress'

// Das Inhaltsverzeichnis der Website ist docs/README.md: jede "## "-Ueberschrift wird eine
// Gruppe, jeder fett gesetzte Link darunter ein Eintrag. Links auf Ordner (adr/, journeys/)
// werden aufklappbare Untergruppen, deren Eintraege aus der README.md des Ordners kommen.
// So gibt es nur ein Inhaltsverzeichnis, und es ist auch auf GitHub lesbar.
//
// Der Linktext in diesen Verzeichnissen ist der kurze Titel einer Seite, in der Seitenleiste
// und in Links, die auf GitHub nur den Dateinamen zeigen. Ist der Linktext selbst ein
// Dateiname, gilt die erste Ueberschrift der Seite.

const TOC_LINK = /^\s*-\s+\*\*\[([^\]]*)\]\(([^)#]+)\)\*\*/
const ANY_LINK = /^\s*-\s+(?:\*\*)?\[([^\]]*)\]\(([^)#]+)\)/

export function firstHeading(file: string): string {
  const match = fs.readFileSync(file, 'utf8').match(/^#\s+(.+)$/m)
  return match ? match[1].replace(/`/g, '').trim() : path.basename(file, '.md')
}

function isFileName(label: string): boolean {
  return /\.md$|\/$/.test(label.replace(/`/g, '').trim())
}

/** Kurze Titel aus den Verzeichnissen: Pfad relativ zu docs/ -> Linktext. */
export function tocLabels(docsDir: string): Map<string, string> {
  const labels = new Map<string, string>()
  const indexes = ['README.md', ...fs
    .readdirSync(docsDir, { withFileTypes: true })
    .filter((d) => d.isDirectory() && fs.existsSync(path.join(docsDir, d.name, 'README.md')))
    .map((d) => `${d.name}/README.md`)]
  for (const index of indexes) {
    const dir = path.dirname(index)
    for (const line of fs.readFileSync(path.join(docsDir, index), 'utf8').split('\n')) {
      const link = line.match(ANY_LINK)
      if (!link || isFileName(link[1])) continue
      const rel = path.posix.normalize(path.posix.join(dir, link[2]))
      const key = rel.endsWith('/') ? `${rel}README.md` : rel
      if (!labels.has(key)) labels.set(key, link[1].replace(/`/g, '').trim())
    }
  }
  return labels
}

export function pageTitle(docsDir: string, rel: string, labels: Map<string, string>): string {
  return labels.get(rel) ?? firstHeading(path.join(docsDir, rel))
}

function pageLink(rel: string): string {
  const withoutExt = rel.replace(/\.md$/, '')
  if (withoutExt === 'README') return '/'
  return '/' + withoutExt.replace(/(^|\/)README$/, '$1')
}

function folderGroup(docsDir: string, folder: string, labels: Map<string, string>): DefaultTheme.SidebarItem {
  const dir = path.join(docsDir, folder)
  const readme = path.join(dir, 'README.md')
  // Reihenfolge wie in der README.md des Ordners; nicht verlinkte Dateien alphabetisch dahinter.
  const listed = fs.existsSync(readme)
    ? fs.readFileSync(readme, 'utf8').split('\n').map((l) => l.match(ANY_LINK)?.[2]).filter((f): f is string => !!f && !f.includes('/') && f.endsWith('.md'))
    : []
  const rest = fs.readdirSync(dir).filter((f) => f.endsWith('.md') && f !== 'README.md' && !listed.includes(f)).sort()
  return {
    text: pageTitle(docsDir, `${folder}/README.md`, labels),
    link: fs.existsSync(readme) ? pageLink(`${folder}/`) : undefined,
    collapsed: true,
    items: [...listed, ...rest].map((f) => ({ text: pageTitle(docsDir, `${folder}/${f}`, labels), link: pageLink(`${folder}/${f}`) })),
  }
}

export function sidebarFromReadme(docsDir: string, excluded: string[]): DefaultTheme.SidebarItem[] {
  const labels = tocLabels(docsDir)
  const groups: DefaultTheme.SidebarItem[] = [{ text: 'Übersicht', link: '/' }]
  let current: DefaultTheme.SidebarItem | undefined

  for (const line of fs.readFileSync(path.join(docsDir, 'README.md'), 'utf8').split('\n')) {
    const heading = line.match(/^##\s+(.+)$/)
    if (heading) {
      current = { text: heading[1].trim(), items: [] }
      groups.push(current)
      continue
    }
    const link = line.match(TOC_LINK)
    if (!current || !link) continue
    const target = link[2]
    if (target.endsWith('/')) {
      current.items!.push(folderGroup(docsDir, target.replace(/\/$/, ''), labels))
    } else if (target.endsWith('.md') && !excluded.includes(target)) {
      current.items!.push({ text: pageTitle(docsDir, target, labels), link: pageLink(target) })
    }
  }
  return groups.filter((g) => !g.items || g.items.length > 0)
}
