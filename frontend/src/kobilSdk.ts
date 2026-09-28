import { KOBIL_TEXTS, resolveText, type TextRef } from './texts'
/**
 * Stands in for KOBIL's MC SDK, which on a real phone is native code inside the app.
 *
 * Not part of api.ts and not signed with a DPoP proof: these calls go to KOBIL, not to this backend
 * (docs/10-frontend.md names the exception to FE-5). The one-time password is all the client
 * learns; the assertion behind it is fetched server-side. Its own file, like deviceKey.ts, so
 * vendor SDK code is recognizable as such.
 */

const KOBIL_BASE = '/mock-kobil'

export interface KobilUserIdentifier {
  tenantId: string
  userId: string
}

async function kobilCall<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(`${KOBIL_BASE}/${path}`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  const text = await response.text()
  if (!response.ok) {
    let message = `KOBIL ${path} failed: ${response.status}`
    try {
      const parsed = JSON.parse(text) as { error?: TextRef }
      if (parsed.error) message = resolveText(parsed.error, KOBIL_TEXTS)
    } catch {
      // The provider answered with something that is not JSON - keep the status line.
    }
    throw new Error(message)
  }
  return JSON.parse(text) as T
}

/**
 * The SDK's `ActivateEvent`: binds this device to the user. The PIN passed in is the one the
 * backend minted and handed over - in the KOBIL standard flow the user would have chosen it.
 */
export async function activate(
  user: KobilUserIdentifier,
  activationCode: string,
  pin: string
): Promise<{ deviceId: string }> {
  return kobilCall('activate', { ...user, activationCode, pin })
}

/**
 * The SDK's `LoginEvent`: KOBIL checks the device and files an assertion. All that comes back is
 * the one-time password pointing at it.
 */
export async function login(user: KobilUserIdentifier, pin: string): Promise<{ otp: string }> {
  return kobilCall('login', { ...user, pin })
}
