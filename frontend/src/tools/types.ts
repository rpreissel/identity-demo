import type { ReactNode } from 'react'
import type { CallerProof } from '../callerProof'
import type { ChannelResponse, DemoInfo, StepData } from '../types'

/**
 * Everything a tool's render() needs to draw its step and call its own api.ts. Assembled once in
 * AppChannelApp.tsx from `next`/`activeTool`, so adding or removing a tool touches no central props.
 */
export interface ToolRenderContext {
  step: string
  toolId: string
  /**
   * Set once a ToolSession exists for this step (docs/05-api.md #2). Device tools build their
   * DPoP-proof htu from it.
   */
  toolSessionId?: string
  /** The App channel's DPoP key ([CallerProof]), read by submitViaPatch in tools/shared/defaultApi.ts. */
  proof: CallerProof
  stepData?: StepData
  /**
   * A note from the journey shown above the tool's form (e.g. why this step appears). Its own field,
   * not in `stepData`: it comes from the orchestrator's `message` step before the tool and must
   * survive the tool's own response.
   */
  message?: string
  demo?: DemoInfo
  /** App.tsx: applyResponse(response, toolId) */
  onResult: (response: ChannelResponse) => void
  /** App.tsx: setError(message) */
  onError: (message: string) => void
  /**
   * Abandons this tool, the same backend-handled abandon the surrounding view offers. Handed to the
   * tool so an optional step can put the way out next to its submit button. Only tools with
   * [ToolMeta.skipLabel] render it; the surrounding view then leaves its own out.
   */
  onSkip?: () => void
}

/**
 * What only the app adds to a tool: its symbol, and how its way out is worded. Name and hint come
 * from the backend's catalog (toolCatalog.ts); [metaFor] puts the two together.
 */
export interface ToolModuleMeta {
  icon: string
  /**
   * Label for the abandon button when "Zurück" is the wrong word: an optional step rather than one
   * of several ways (ident-kvnr: "jetzt nicht", the run carries on without the register binding).
   * Only wording; the button is the same abandon (DELETE /tools/api/{toolId}/v{version}/{id}).
   */
  skipLabel?: string
}

/** A tool as the app shows it: its own [ToolModuleMeta] plus name and hint from the catalog. */
export interface ToolMeta extends ToolModuleMeta {
  label: string
  hint: string
  /**
   * The account method an enrollment tool sets up (`ActiveMethodView.method`), from the catalog.
   * With it the choice of methods shows one that is already set up in its place, marked as such,
   * instead of dropping it (docs/10-frontend.md, "Auswahl der Verfahren").
   */
  enrolls?: string
}

/**
 * Demo column (StepExplanation): what a step of this tool does and who is acting on it right now -
 * the tool knows its own steps, so it says so itself rather than a central list that could miss one.
 */
export interface StepExplanation {
  does: string
  actor: string
}

/** One toolId's registration: its display meta and its own step -> form rendering. */
export interface ToolModule {
  toolId: string
  /**
   * The one version of the tool's contract this client speaks (ADR-51): declared in
   * `availableTools` as `<toolId>@<version>` and called under `/tools/api/<toolId>/v<version>`.
   */
  version: number
  meta: ToolModuleMeta
  /** Required, so a new tool cannot leave the demo column silent about its steps. */
  explain(step: string): StepExplanation
  /** Returns null when `ctx.step` isn't one of this tool's own steps. */
  render(ctx: ToolRenderContext): ReactNode | null
}
