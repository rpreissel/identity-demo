import type { AuthEmailLookupPatchRequest, AuthEmailPatchRequest, ConfirmEmailPatchRequest } from '../../generated/models'
import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

export function confirmEmail(ctx: ToolRenderContext, email: string) {
  return submitViaPatch(ctx, { email } satisfies ConfirmEmailPatchRequest)
}

export function submitEmailCode(ctx: ToolRenderContext, code: string) {
  return submitViaPatch(ctx, { code } satisfies AuthEmailPatchRequest & ConfirmEmailPatchRequest)
}

export function requestEmailLookup(ctx: ToolRenderContext, email: string) {
  return submitViaPatch(ctx, { email } satisfies AuthEmailLookupPatchRequest)
}
