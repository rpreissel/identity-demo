import { describeError, patchTool } from '../../api'
import type { ToolRenderContext } from '../types'
import { t } from '../../texts'

/**
 * Default "finish this step via PATCH" implementation: a convenience a tool's own api.ts may
 * re-export (e.g. tools/sms/api.ts), not a contract every tool must use. The one place `ctx.proof`
 * is read, so every tool's render() stays free of how the call is signed.
 */
export function submitViaPatch(ctx: ToolRenderContext, body: Record<string, unknown>) {
  if (!ctx.toolSessionId) return
  return patchTool(ctx.proof.dpop, ctx.toolSessionId, ctx.toolId, body).then(ctx.onResult).catch((err) => ctx.onError(describeError(t('Anfrage fehlgeschlagen'), err)))
}
