import { t } from './texts'

/**
 * The areas of the demo, in the order the shell's navigation lists them (ChannelNav). Each opens
 * in a tab of its own, addressed by name: a link from any page brings that area's tab to the front
 * instead of opening a second copy. The start page has its own tab (startWindow.ts).
 */
export type Area = 'app' | 'web' | 'pv' | 'mail' | 'admin'

export const APP_TAB = 'identity-demo-app-kanal'
export const WEB_TAB = 'identity-demo-web-kanal'
export const REGISTER_TAB = 'identity-demo-register'
export const MAILBOX_TAB = 'identity-demo-briefkasten'
export const ADMIN_TAB = 'identity-demo-admin'

export interface AreaLink {
  key: Area
  href: string
  target: string
}

export const AREA_LINKS: readonly AreaLink[] = [
  { key: 'app', href: '/app/', target: APP_TAB },
  { key: 'web', href: '/web/', target: WEB_TAB },
  { key: 'pv', href: '/personenverzeichnis/', target: REGISTER_TAB },
  { key: 'mail', href: '/briefkasten/', target: MAILBOX_TAB },
  { key: 'admin', href: '/admin/', target: ADMIN_TAB },
]

/** The area's name as the navigation and the start page show it. */
export function areaLabel(area: Area): string {
  switch (area) {
    case 'app':
      return t('App')
    case 'web':
      return t('Website')
    case 'pv':
      return t('Personenverzeichnis')
    case 'mail':
      return t('Briefkasten')
    case 'admin':
      return t('Verwaltung')
  }
}
