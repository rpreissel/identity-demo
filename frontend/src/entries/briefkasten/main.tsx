import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import '../../index.css'
import { APP_TEXTS, loadAllTexts } from '../../texts'

// Texts first, then the page: its own t("...") calls must find their wordings when they run.
void loadAllTexts(APP_TEXTS)
  .then(() => import('./BriefkastenApp.tsx'))
  .then(({ BriefkastenApp }) =>
    createRoot(document.getElementById('root')!).render(
      <StrictMode>
        <BriefkastenApp />
      </StrictMode>,
    ),
  )
