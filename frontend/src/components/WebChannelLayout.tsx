import type { ReactNode } from 'react'
import { ChannelNav } from './ChannelNav'

interface Props {
  children: ReactNode
}

/**
 * Chrome for the whole Web channel - the shell's header (ChannelNav) in the Website's green
 * (.channel-web, index.css).
 * The page lays itself out (the website's browser window next to the demo column). No tabs: the
 * journey trace and the operator settings live on /admin/.
 */
export function WebChannelLayout({ children }: Props) {
  return (
    <div className="web-shell channel-web">
      <ChannelNav area="web" />
      {children}
    </div>
  )
}
