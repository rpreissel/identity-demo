import type { IdentEidPatchRequest } from '../../generated/models'
import { submitViaPatch } from '../shared/defaultApi'
import type { ToolRenderContext } from '../types'

/** What the simulated card shows, read in one go. */
export interface EidCard {
  familyName: string
  givenNames: string
  /** ISO date (YYYY-MM-DD), as `<input type="date">` delivers it. */
  birthDate: string
  /** Street and house number in one line - the card's `Street` carries both. */
  streetAddress: string
  postalCode: string
  locality: string
  restrictedId: string
}

export interface EidFields extends EidCard {
  pin: string
}

/** Any subset: the backend merges each PATCH onto what it already has (docs/verfahren/eid.md). */
export function submitEid(ctx: ToolRenderContext, fields: Partial<EidFields>) {
  return submitViaPatch(ctx, fields satisfies IdentEidPatchRequest)
}
