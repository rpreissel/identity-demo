import { createContext } from 'react'

/**
 * Where demo-only helpers go: the aside next to the card (Layout.tsx), not into the form, so the
 * page keeps the layout a real visitor sees. Null until the aside is mounted.
 */
export const DemoSlotContext = createContext<HTMLElement | null>(null)
