import type { Next } from './types'

/**
 * Screens the orchestrator itself owns: selection pages, confirmations, the finished screen.
 * `context` names the kind of offer (`auth`/`enrollment`/`registration`), not the intent asking
 * for it. A new intent needs a new row only for a new kind of screen (like `accountDeletion`'s
 * confirmation). Tools are in src/tools/registry.ts.
 */
const orchestratorRoutes: Record<string, Record<string, string>> = {
  registration: { selectIdentificationMethod: 'select-method' },
  enrollment: { selectMethod: 'select-method' },
  auth: { selectMethod: 'select-method' },
  authentication: { authenticated: 'authentication-completed' },
  // Every AnswerableState, of any intent, shares this one address (JourneyState.kt) - the screen
  // it renders is always the same generic prompt, driven entirely by stepData.prompt.
  prompt: { confirm: 'prompt' },
}

/**
 * Which orchestrator screen to show, based only on `next`, never on a URL. Tool steps go through
 * renderToolStep in src/tools/registry.ts.
 */
export function getUIComponent(next: Next | undefined): string | null {
  if (!next) return null
  if (next.type === 'orchestrator' && next.context) {
    return orchestratorRoutes[next.context]?.[next.step] ?? null
  }
  return null
}
