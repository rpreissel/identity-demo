<script lang="ts">
// Mermaid verträgt keine parallelen render()-Aufrufe; alle Diagramme einer Seite laufen deshalb
// nacheinander durch eine gemeinsame Warteschlange. Mermaid wird nur einmal geladen.
let mermaidModule: Promise<typeof import('mermaid')['default']> | undefined
let queue: Promise<unknown> = Promise.resolve()
let counter = 0

function renderQueued(code: string, dark: boolean): Promise<string> {
  mermaidModule ??= import('mermaid').then((m) => m.default)
  const job = queue.then(async () => {
    const mermaid = await mermaidModule!
    mermaid.initialize({ startOnLoad: false, securityLevel: 'strict', theme: dark ? 'dark' : 'default' })
    return (await mermaid.render(`mermaid-${counter++}`, code)).svg
  })
  queue = job.catch(() => undefined)
  return job
}
</script>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { useData } from 'vitepress'

const props = defineProps<{ code: string }>()
const { isDark } = useData()
const source = computed(() => decodeURIComponent(props.code))
const svg = ref('')
const error = ref('')

async function render() {
  try {
    svg.value = await renderQueued(source.value, isDark.value)
    error.value = ''
  } catch (e) {
    error.value = String(e)
  }
}

onMounted(render)
watch([isDark, source], render)
</script>

<template>
  <div class="mermaid-diagram">
    <div v-if="svg" v-html="svg" />
    <details v-else-if="error" class="mermaid-error">
      <summary>Diagramm konnte nicht gezeichnet werden</summary>
      <pre>{{ error }}</pre>
      <pre>{{ source }}</pre>
    </details>
    <div v-else class="mermaid-loading">Diagramm wird gezeichnet …</div>
  </div>
</template>
