import { boot } from '../../boot'
import { APP_TEXTS, KOBIL_TEXTS, loadAllTexts } from '../../texts'
import { loadToolCatalog } from '../../toolCatalog'

// Texts and the tool catalog first, then the app: the backend sends text references only, the
// app's own t("...") calls - module-level ones included - must find their wordings when they run,
// and the tool registry reads each tool's name and hint from the catalog (metaFor).
boot(Promise.all([loadAllTexts(APP_TEXTS, KOBIL_TEXTS), loadToolCatalog()]), () => import('./AppChannelApp.tsx').then((m) => m.AppChannelApp))
