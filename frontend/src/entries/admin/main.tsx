import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '../../index.css'
import { APP_TEXTS, loadAllTexts } from '../../texts'
import { loadToolCatalog } from '../../toolCatalog'

// Texts first, then the app: the backend sends text references only, and the app's own t("...")
// calls - module-level ones included (tool labels) - must find their wordings when they run. The
// tool catalog gives the tool settings their names.
void Promise.all([loadAllTexts(APP_TEXTS), loadToolCatalog()])
  .then(() => import('./AdminApp.tsx'))
  .then(({ AdminApp }) =>
    createRoot(document.getElementById('root')!).render(
      <StrictMode>
        <AdminApp />
      </StrictMode>,
    ),
  )
