import type { AuthSmsLookupPatchRequest, AuthSmsPatchRequest, EnrollSmsPatchRequest } from '../../generated/models'
import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

export function enrollSmsNumber(ctx: ToolRenderContext, phoneNumber: string) {
  return submitViaPatch(ctx, { phoneNumber } satisfies EnrollSmsPatchRequest)
}

export function submitSmsTan(ctx: ToolRenderContext, tan: string) {
  return submitViaPatch(ctx, { tan } satisfies AuthSmsPatchRequest & EnrollSmsPatchRequest)
}

export function requestSmsLookup(ctx: ToolRenderContext, email: string) {
  return submitViaPatch(ctx, { email } satisfies AuthSmsLookupPatchRequest)
}
