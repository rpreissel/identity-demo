import { useState } from 'react'
import { Disclosure } from './Disclosure'
import type { ActiveMethodView } from '../types'

export interface DebugEvent {
  id: number
  time: string
  label: string
  request?: unknown
  response?: unknown
  error?: string
}

interface DebugSidebarProps {
  channel: {
    channelSessionId?: string
    channelState?: string
    currentAcr?: string
    currentAmr?: string[]
    activeMethods?: ActiveMethodView[]
    next?: unknown
    stepData?: unknown
    demo?: unknown
    activeTool?: unknown
  }
  log: DebugEvent[]
}

/** Strips a top-level `demo` key, if present - the only place it ever appears in these JSON blobs. */
function withoutDemo(value: unknown): unknown {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return value
  const { demo, ...rest } = value as Record<string, unknown>
  return rest
}

/**
 * The request log in the demo column's background: a demo shows what happens under the hood at
 * every step. Collapsed, so raw JSON is not the first thing a visitor sees. `demo`
 * (accountId/personId/journeys/tan/...) is hidden by default: it is a demo aid, not what the
 * backend did, and the most noise once a journey chain runs.
 */
export function DebugSidebar({ channel, log }: DebugSidebarProps) {
  const [showDemo, setShowDemo] = useState(false)
  return (
    <Disclosure summary="Request Einblicke" lazy>
      <div className="request-log">
        <p className="request-log__intro">Jeder API-Aufruf, den die Oberfläche gerade macht - so sieht das Backend-Protokoll live aus.</p>

        <label className="request-log__toggle">
          <input type="checkbox" checked={showDemo} onChange={(e) => setShowDemo(e.target.checked)} />
          Demo-Infos einblenden (accountId, personId, Journey-Kette, TAN/Passwort-Vorbelegung)
        </label>

        <section>
          <h3>Kanal</h3>
          <pre>{JSON.stringify(showDemo ? channel : withoutDemo(channel), null, 2)}</pre>
        </section>

        <section>
          <h3>Verlauf ({log.length})</h3>
          <ul className="debug-log">
            {log.map((entry) => (
              <li key={entry.id}>
                <div className="debug-log-header">
                  <span className="debug-log-time">{entry.time}</span>
                  <span className="debug-log-label">{entry.label}</span>
                </div>
                {entry.request !== undefined && (
                  <div className="debug-log-block">
                    <span className="debug-log-block-label">Request</span>
                    <pre>{JSON.stringify(entry.request, null, 2)}</pre>
                  </div>
                )}
                {entry.response !== undefined && (
                  <div className="debug-log-block">
                    <span className="debug-log-block-label">Response</span>
                    <pre>{JSON.stringify(showDemo ? entry.response : withoutDemo(entry.response), null, 2)}</pre>
                  </div>
                )}
                {entry.error && (
                  <div className="debug-log-block">
                    <span className="debug-log-block-label">Fehler</span>
                    <pre>{entry.error}</pre>
                  </div>
                )}
              </li>
            ))}
          </ul>
        </section>
      </div>
    </Disclosure>
  )
}
