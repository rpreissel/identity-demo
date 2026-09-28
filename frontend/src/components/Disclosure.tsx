import { useState, type ReactNode } from 'react'

interface DisclosureProps {
  summary: string
  children: ReactNode
  defaultOpen?: boolean
  /** Renders the body only while open - for large, live content such as the request log. */
  lazy?: boolean
}

/**
 * Native <details>/<summary> toggle for the raw/technical part of a card (JWT claims dumps, token
 * strings) and for the demo column's collapsed sections - so screens lead with status and actions,
 * while the rest stays one click away instead of being dropped.
 */
export function Disclosure({ summary, children, defaultOpen = false, lazy = false }: DisclosureProps) {
  const [open, setOpen] = useState(defaultOpen)
  return (
    <details className="disclosure" open={defaultOpen} onToggle={(e) => setOpen(e.currentTarget.open)}>
      <summary className="disclosure-summary">{summary}</summary>
      <div className="disclosure-body">{!lazy || open ? children : null}</div>
    </details>
  )
}
