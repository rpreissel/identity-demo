import { createContext, useContext, useEffect, useLayoutEffect, useRef } from 'react'

/** Where a tool announces a previous screen of its own; the frame's "Zurück" then goes there. */
export type InnerBackRegistry = { set: (handler: (() => void) | null) => void }

const InnerBackContext = createContext<InnerBackRegistry | undefined>(undefined)

export const InnerBackProvider = InnerBackContext.Provider

/**
 * A tool with screens of its own (e.g. address, then code) registers [handler] as "Zurück": a pure
 * screen change inside the tool, no server call. `null` means the tool is on its first screen, so
 * "Zurück" leaves the tool (a process step).
 */
export function useInnerBack(handler: (() => void) | null) {
  const registry = useContext(InnerBackContext)
  const latest = useRef(handler)
  useLayoutEffect(() => {
    latest.current = handler
  })
  const active = handler !== null
  useEffect(() => {
    if (!registry || !active) return
    registry.set(() => latest.current?.())
    return () => registry.set(null)
  }, [registry, active])
}
