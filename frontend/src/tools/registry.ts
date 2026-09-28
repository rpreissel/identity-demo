import { createElement, type ReactNode } from 'react'
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

export function metaFor(toolId: string): ToolMeta {
  return BY_ID[toolId]?.meta ?? { icon: '🔐', label: toolId, hint: '' }
}

/** The enrollment tool that sets up `method`, for showing an account's method like its choice. */
export function enrollmentToolFor(method: string): string | undefined {
  return TOOL_MODULES.find((module) => module.meta.enrolls === method)?.toolId
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
