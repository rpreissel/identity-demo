import { ApiError } from './api'
import { resolveText, type TextRef } from './texts'

/**
 * The fetch of a simulated third-party system under [base]. Such a system answers in its own form
 * ({"error": <text reference>}), worded in its own text bundle [texts] - not with our ErrorResponse.
 */
export function mockApi(base: string, texts: string) {
  return async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
    const response = await fetch(base + path, {
      method,
      headers: { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const text = await response.text()
    let parsed: { error?: TextRef } | undefined
    try {
      parsed = text === '' ? undefined : JSON.parse(text)
    } catch {
      parsed = undefined
    }
    if (!response.ok) {
      const reason = parsed?.error ? resolveText(parsed.error, texts) : undefined
      throw new ApiError(response.status, undefined, reason ?? `${method} ${path}: ${response.status}`)
    }
    return parsed as T
  }
}
