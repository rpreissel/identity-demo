/**
 * The welcome page opens every app in a tab of its own (named targets, see WelcomeApp), so
 * "← Startseite" must not turn the app's tab into a second welcome page - it switches back to the
 * tab the demo started in. That tab carries this name; the welcome page sets it on load.
 */
export const START_WINDOW = 'identity-demo-start'

export function markAsStartWindow(): void {
  window.name = START_WINDOW
}

/**
 * Brings the start tab to the front. Browsers find a named tab only among the tabs that opened one
 * another, so a tab opened by hand (address bar, bookmark) finds none: then the start page opens in
 * a new tab, and this one keeps what it shows.
 */
export function goToStart(): void {
  if (window.name === START_WINDOW) {
    window.location.href = '/'
    return
  }
  // An empty URL addresses the named tab without navigating it - its tab and scroll state stay.
  const start = window.open('', START_WINDOW)
  if (!start) {
    // Popups blocked: the only way left is this tab.
    window.location.href = '/'
    return
  }
  let isFreshBlank = false
  try {
    isFreshBlank = start.location.href === 'about:blank'
  } catch {
    // Not readable means some other origin lives there - not our start page either.
    isFreshBlank = true
  }
  // No start tab was found: window.open just made an empty one under that name - the start page goes there.
  if (isFreshBlank) start.location.href = '/'
  start.focus()
}

/**
 * Names this tab after its area (areas.ts), so links from the start page and the other areas bring it
 * to the front instead of opening a second copy - also when it was opened by hand.
 */
export function markAsAreaWindow(target: string): void {
  if (window.name !== target) window.name = target
}
