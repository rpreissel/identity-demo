// Jeder Unterordner von docs/ hat eine README.md, die GitHub beim Blaettern und die Website als
// Startseite des Ordners zeigt. Dieser Check schlaegt fehl, wenn eine Datei darin nicht verlinkt
// ist, damit die Ordnerseiten nicht still veralten. Fuer adr/ ist 12-entscheidungen.md der Index.
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const docsDir = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../docs')
const indexOf = { adr: '12-entscheidungen.md' }

const problems = []
for (const folder of fs.readdirSync(docsDir, { withFileTypes: true })) {
  if (!folder.isDirectory() || folder.name === 'media' || folder.name.startsWith('.')) continue
  const dir = path.join(docsDir, folder.name)
  const readme = path.join(dir, 'README.md')
  if (!fs.existsSync(readme)) {
    problems.push(`docs/${folder.name}/README.md fehlt`)
    continue
  }
  const indexFile = indexOf[folder.name] ? path.join(docsDir, indexOf[folder.name]) : readme
  const index = fs.readFileSync(indexFile, 'utf8')
  const prefix = indexFile === readme ? '' : `${folder.name}/`
  for (const file of fs.readdirSync(dir)) {
    if (!file.endsWith('.md') || file === 'README.md') continue
    if (!index.includes(`](${prefix}${file}`)) {
      problems.push(`docs/${folder.name}/${file} ist nicht in ${path.relative(docsDir, indexFile)} verlinkt`)
    }
  }
}

if (problems.length) {
  console.error(problems.map((p) => `- ${p}`).join('\n'))
  process.exit(1)
}
