import type { ToolCatalogEntry } from './generated/models'

/**
 * What the backend calls each tool: name and hint as text references, declared once in the tool's
 * module (docs/03-tool-architektur.md #2, `GET /tools/catalog`). Loaded before the app's code like
 * the texts (main.tsx), so the tool registry can read it synchronously.
 */
let entries = new Map<string, ToolCatalogEntry>()

export async function loadToolCatalog(): Promise<void> {
  try {
    // Imported here, not at the top: the unit tests install their fixture without pulling in api.ts.
    const { fetchToolCatalog } = await import('./api')
    installToolCatalog(await fetchToolCatalog())
  } catch {
    // Without it the app still works; tools show their id instead of their name.
  }
}

/** Replaces the catalog - main.tsx after loading, the unit tests with their fixture. */
export function installToolCatalog(list: ToolCatalogEntry[]): void {
  entries = new Map(list.map((entry) => [entry.toolId, entry]))
}

export function catalogEntry(toolId: string): ToolCatalogEntry | undefined {
  return entries.get(toolId)
}

/** The tool that sets up `method` (role ENROLLMENT), for showing an account's method by its name. */
export function enrollmentToolOf(method: string): string | undefined {
  for (const entry of entries.values()) {
    if (entry.role === 'ENROLLMENT' && entry.method === method) return entry.toolId
  }
  return undefined
}
