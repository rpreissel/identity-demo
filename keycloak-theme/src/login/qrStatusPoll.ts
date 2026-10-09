/**
 * The QR waiting page asks the extension's status endpoint in the background and posts its form
 * only once something changed (docs/adr/ADR-045-qr-warteseite-fragt-im-hintergrund.md). Only an explicit `waiting` keeps
 * the page; an unreachable endpoint is asked again.
 */

export const POLL_INTERVAL_MS = 2000

export type QrStatus = 'waiting' | 'ready' | 'unreachable'

export async function checkQrStatus(statusUrl: string, fetchFn: typeof fetch = fetch): Promise<QrStatus> {
  let response: Response
  try {
    response = await fetchFn(statusUrl, { credentials: 'same-origin', cache: 'no-store', headers: { Accept: 'application/json' } })
  } catch {
    return 'unreachable'
  }
  if (!response.ok) return 'ready'
  try {
    const body = (await response.json()) as { state?: unknown }
    return body.state === 'waiting' ? 'waiting' : 'ready'
  } catch {
    return 'ready'
  }
}

/** Asks every `interval` ms until the status is no longer `waiting`, then calls `onChange` once. Returns a stop function. */
export function pollQrStatus(check: () => Promise<QrStatus>, onChange: () => void, interval = POLL_INTERVAL_MS): () => void {
  let stopped = false
  let timer: ReturnType<typeof setTimeout> | undefined
  const next = () => {
    timer = setTimeout(async () => {
      const status = await check()
      if (stopped) return
      if (status === 'ready') {
        stopped = true
        onChange()
      } else {
        next()
      }
    }, interval)
  }
  next()
  return () => {
    stopped = true
    clearTimeout(timer)
  }
}
