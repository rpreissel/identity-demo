/**
 * The app's half of the KOBIL credential: a secret handed out once at setup, which the backend
 * demands before it releases the PIN. On a real device it sits in the keystore behind a biometric
 * prompt, here in `localStorage` behind a simulated one (like deviceKey.ts's demo gate). The server
 * verifies possession of the secret, not how the user was asked. Keyed by the KOBIL user id, so a
 * second setup does not overwrite the first one's secret.
 */

const KEY_PREFIX = 'kobil-unlock-secret:'

export function storeUnlockSecret(kobilUserId: string, secret: string): void {
  localStorage.setItem(KEY_PREFIX + kobilUserId, secret)
}

export function loadUnlockSecret(kobilUserId: string): string | null {
  return localStorage.getItem(KEY_PREFIX + kobilUserId)
}

/**
 * Drops every secret this browser holds, once the backend reports no KOBIL binding for this device.
 * All of them: the browser is the device, so each secret belonged to a binding on this very key.
 * Once that key carries no binding (rebound, revoked), none of them is good for anything.
 */
export function forgetAllUnlockSecrets(): void {
  Object.keys(localStorage)
    .filter((key) => key.startsWith(KEY_PREFIX))
    .forEach((key) => localStorage.removeItem(key))
}
