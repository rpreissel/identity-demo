import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { checkQrStatus, pollQrStatus, POLL_INTERVAL_MS, type QrStatus } from './qrStatusPoll'

const STATUS_URL = 'https://kc.example/realms/Demo/orchestrator-qr/status?client_id=web&tab_id=t1'

function answering(status: number, body?: string): typeof fetch {
  return vi.fn(async () => new Response(body ?? null, { status })) as unknown as typeof fetch
}

describe('checkQrStatus', () => {
  it('reads waiting and ready from the answer', async () => {
    expect(await checkQrStatus(STATUS_URL, answering(200, '{"state":"waiting"}'))).toBe('waiting')
    expect(await checkQrStatus(STATUS_URL, answering(200, '{"state":"ready"}'))).toBe('ready')
  })

  it('asks with the page cookies of its own origin only, never from a cache', async () => {
    const fetchFn = answering(200, '{"state":"waiting"}')
    await checkQrStatus(STATUS_URL, fetchFn)
    expect(fetchFn).toHaveBeenCalledWith(STATUS_URL, expect.objectContaining({ credentials: 'same-origin', cache: 'no-store' }))
  })

  it('treats a refusal or an unreadable answer as ready, so the form post shows what happened', async () => {
    expect(await checkQrStatus(STATUS_URL, answering(404))).toBe('ready')
    expect(await checkQrStatus(STATUS_URL, answering(502, 'bad gateway'))).toBe('ready')
    expect(await checkQrStatus(STATUS_URL, answering(200, 'not json'))).toBe('ready')
  })

  it('treats a network failure as unreachable, not as a change', async () => {
    const failing = vi.fn(async () => {
      throw new TypeError('network')
    }) as unknown as typeof fetch
    expect(await checkQrStatus(STATUS_URL, failing)).toBe('unreachable')
  })
})

describe('pollQrStatus', () => {
  beforeEach(() => vi.useFakeTimers())
  afterEach(() => vi.useRealTimers())

  function scripted(...answers: QrStatus[]) {
    return vi.fn(async () => answers.shift() ?? 'waiting')
  }

  it('keeps the page while waiting and submits exactly once when ready', async () => {
    const check = scripted('waiting', 'unreachable', 'waiting', 'ready')
    const onChange = vi.fn()
    pollQrStatus(check, onChange)

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3)
    expect(check).toHaveBeenCalledTimes(3)
    expect(onChange).not.toHaveBeenCalled()

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 5)
    expect(check).toHaveBeenCalledTimes(4)
    expect(onChange).toHaveBeenCalledTimes(1)
  })

  it('asks no more than once per interval', async () => {
    const check = scripted()
    pollQrStatus(check, vi.fn())
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS - 1)
    expect(check).not.toHaveBeenCalled()
    await vi.advanceTimersByTimeAsync(1)
    expect(check).toHaveBeenCalledTimes(1)
  })

  it('stops for good once stopped, also with an answer still on its way', async () => {
    let answer: (status: QrStatus) => void = () => {}
    const check = vi.fn(() => new Promise<QrStatus>((resolve) => (answer = resolve)))
    const onChange = vi.fn()
    const stop = pollQrStatus(check, onChange)

    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS)
    stop()
    answer('ready')
    await vi.advanceTimersByTimeAsync(POLL_INTERVAL_MS * 3)
    expect(onChange).not.toHaveBeenCalled()
    expect(check).toHaveBeenCalledTimes(1)
  })
})
