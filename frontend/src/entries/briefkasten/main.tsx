import { boot } from '../../boot'
import { APP_TEXTS, loadAllTexts } from '../../texts'

// Texts first, then the page: its own t("...") calls must find their wordings when they run.
boot(loadAllTexts(APP_TEXTS), () => import('./BriefkastenApp.tsx').then((m) => m.BriefkastenApp))
