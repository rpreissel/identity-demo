import type { AuthPasswordLookupPatchRequest, AuthPasswordPatchRequest, EnrollPasswordPatchRequest } from '../../generated/models'
import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

export function enrollPassword(ctx: ToolRenderContext, fields: Required<EnrollPasswordPatchRequest>) {
  return submitViaPatch(ctx, fields)
}

export function submitPassword(ctx: ToolRenderContext, fields: Required<AuthPasswordPatchRequest>) {
  return submitViaPatch(ctx, fields)
}

export function submitPasswordLookup(ctx: ToolRenderContext, fields: Required<AuthPasswordLookupPatchRequest>) {
  return submitViaPatch(ctx, fields)
}
