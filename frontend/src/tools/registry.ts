import { createElement, type ReactNode } from 'react'
import { resolveText } from '../texts'
import { catalogEntry, enrollmentToolOf } from '../toolCatalog'
import type { StepExplanation, ToolMeta, ToolModule, ToolRenderContext } from './types'

/**
 * Auto-discovers every tool folder's default export (its ToolModule[]): adding or removing a tool
 * means adding or removing a `tools/<name>/index.tsx`. `eager` because knownToolIds is read
 * synchronously at render time. `shared/` has no index.tsx and is not matched.
 */
const discovered = import.meta.glob<{ default: ToolModule[] }>('./*/index.tsx', { eager: true })

const TOOL_MODULES: ToolModule[] = Object.values(discovered).flatMap((mod) => mod.default)

const BY_ID: Record<string, ToolModule> = Object.fromEntries(TOOL_MODULES.map((module) => [module.toolId, module]))

/**
 * The toolIds this client can render, what it can honestly declare as `availableTools`
 * (docs/03-tool-architektur.md, availability).
 */
export const knownToolIds: string[] = TOOL_MODULES.map((module) => module.toolId)

/**
 * A tool as the app shows it: its symbol from its own module, name and hint from the backend's
 * catalog, declared once in the tool's module there (docs/03-tool-architektur.md #2). A tool the
 * catalog does not know shows its id.
 */
export function metaFor(toolId: string): ToolMeta {
  const own = BY_ID[toolId]?.meta ?? { icon: '🔐' }
  const entry = catalogEntry(toolId)
  return {
    ...own,
    label: entry ? resolveText(entry.name) : toolId,
    hint: entry ? resolveText(entry.hint) : '',
    enrolls: entry?.role === 'ENROLLMENT' ? entry.method : undefined,
  }
}

/** The enrollment tool that sets up `method`, for showing an account's method like its choice. */
export function enrollmentToolFor(method: string): string | undefined {
  return enrollmentToolOf(method)
}

/** What `step` of `toolId` does and who is at it - undefined for a tool this client doesn't know. */
export function explainToolStep(toolId: string, step: string): StepExplanation | undefined {
  return BY_ID[toolId]?.explain(step)
}

/**
 * Renders the current step of `ctx.toolId`'s own module, or null if that tool/step is unknown.
 * The journey's message says why the step comes now; it leads into the step as a plain line, not
 * as a box of its own.
 */
export function renderToolStep(ctx: ToolRenderContext): ReactNode | null {
  const content = BY_ID[ctx.toolId]?.render(ctx) ?? null
  const message = ctx.message ?? null
  if (!content) return null
  if (!message) return content
  return createElement('div', null, createElement('p', { className: 'step-context' }, message), content)
}
