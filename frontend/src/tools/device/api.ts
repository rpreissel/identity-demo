import type { DeviceProofPatchRequest } from '../../generated/models'
import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

export function enrollDevice(ctx: ToolRenderContext, body: DeviceProofPatchRequest) {
  return submitViaPatch(ctx, body)
}

export function authDevice(ctx: ToolRenderContext, body: DeviceProofPatchRequest) {
  return submitViaPatch(ctx, body)
}
