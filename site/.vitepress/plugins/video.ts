import type MarkdownIt from 'markdown-it'

// Ein Absatz, der nur aus einem Link auf ein Video besteht, wird auf der Website ein Player.
// Auf GitHub bleibt es ein Link, und GitHub spielt die Datei in seiner Dateiansicht ab.
// Der Player streamt: GitHub Pages beantwortet Range-Anfragen, Vorspulen laedt nicht alles.

const VIDEO = /\.(mp4|webm)(#.*)?$/i

export function videoLinks(md: MarkdownIt) {
  md.core.ruler.push('video-links', (state) => {
    const tokens = state.tokens
    for (let i = 0; i + 2 < tokens.length; i++) {
      if (tokens[i].type !== 'paragraph_open' || tokens[i + 2].type !== 'paragraph_close') continue
      const children = (tokens[i + 1].children ?? []).filter((t) => !(t.type === 'text' && !t.content.trim()))
      if (children.length !== 3 || children[0].type !== 'link_open' || children[2].type !== 'link_close') continue
      const href = children[0].attrGet('href') ?? ''
      if (!VIDEO.test(href)) continue

      // :src statt src: Vue wuerde ein relatives src als Import buendeln. Die Datei wird aber
      // unter ihrem Pfad kopiert (config.mts, buildEnd), damit auch der Link daneben gilt.
      const src = md.utils.escapeHtml(href)
      const label = md.utils.escapeHtml(children[1].content)
      const html = new state.Token('html_block', '', 0)
      html.content =
        `<figure class="doc-video">` +
        `<video controls preload="metadata" playsinline :src="${md.utils.escapeHtml(JSON.stringify(href))}"></video>` +
        `<figcaption><a href="${src}" download>${label}</a></figcaption>` +
        `</figure>\n`
      tokens.splice(i, 3, html)
    }
  })
}
