import type { ReactNode } from 'react'

/**
 * A failure the page shows, announced to screen readers. With a [title]: as a card of its own,
 * for a page whose content the failure replaces. The caller words the title, so it stays in the
 * text bundle of its page.
 */
export function ErrorCard({ children, title }: { children: ReactNode; title?: string }) {
  return title ? (
    <div className="card error-card" role="alert">
      <h2>{title}</h2>
      <p>{children}</p>
    </div>
  ) : (
    <p className="error-card" role="alert">{children}</p>
  )
}
