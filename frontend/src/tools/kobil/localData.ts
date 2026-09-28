import { forgetAllUnlockSecrets } from '../../kobilUnlockSecret'
import type { BoundCredentialView } from '../../types'

const KOBIL_METHOD = 'kobil'

/**
 * Drops this browser's KOBIL unlock secrets once the backend no longer lists a kobil binding for
 * this device. Lives in the tool's folder: what "local data" means is this module's business.
 * Server-side, a rebind already revoked the credential (docs/09-dpop.md). This is the half no
 * backend can do, and here what lies around is a secret, not merely an unusable key.
 */
export function dropStaleKobilData(boundCredentials: BoundCredentialView[] | undefined): void {
  const stillBound = (boundCredentials ?? []).some((credential) => credential.method === KOBIL_METHOD)
  if (!stillBound) forgetAllUnlockSecrets()
}
