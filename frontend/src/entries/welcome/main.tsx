import { boot } from '../../boot'
import { APP_TEXTS, loadAllTexts } from '../../texts'

// Texts first, then the app: the backend sends text references only, and the app's own t("...")
// calls - module-level ones included (tool labels) - must find their wordings when they run.
boot(loadAllTexts(APP_TEXTS), () => import('./WelcomeApp.tsx').then((m) => m.WelcomeApp))
