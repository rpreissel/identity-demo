import type MarkdownIt from 'markdown-it'

// ```mermaid-Bloecke rendert GitHub selbst. Auf der Website werden sie zur Komponente
// <Mermaid>, die das Diagramm im Browser zeichnet (theme/Mermaid.vue).

export function mermaidFences(md: MarkdownIt) {
  const fence = md.renderer.rules.fence!
  md.renderer.rules.fence = (tokens, idx, options, env, self) => {
    const token = tokens[idx]
    if (token.info.trim() !== 'mermaid') return fence(tokens, idx, options, env, self)
    return `<Mermaid code="${encodeURIComponent(token.content)}" />\n`
  }
}
