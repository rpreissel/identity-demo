import { boot } from '../../boot'
import { APP_TEXTS, loadAllTexts } from '../../texts'
import { loadToolCatalog } from '../../toolCatalog'

// Texts first, then the app: the backend sends text references only, and the app's own t("...")
// calls - module-level ones included (tool labels) - must find their wordings when they run. The
// tool catalog gives the tool settings their names.
boot(Promise.all([loadAllTexts(APP_TEXTS), loadToolCatalog()]), () => import('./AdminApp.tsx').then((m) => m.AdminApp))
