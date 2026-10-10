import { StrictMode, type ComponentType } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'

/**
 * Starts a page once [ready] - its texts, for some the tool catalog - is loaded. The backend sends
 * text references only, and the page's own t("...") calls, module-level ones included, must find
 * their wordings when they run. So the page module is imported only afterwards.
 */
export function boot(ready: Promise<unknown>, page: () => Promise<ComponentType>) {
  void ready.then(page).then((Page) =>
    createRoot(document.getElementById('root')!).render(
      <StrictMode>
        <Page />
      </StrictMode>,
    ),
  )
}
