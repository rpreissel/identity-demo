import '@testing-library/jest-dom/vitest'
import { cleanup } from '@testing-library/react'
import { afterEach } from 'vitest'
import { textId } from '../texts'
import { installToolCatalog } from '../toolCatalog'
import catalog from '../tools/catalog.fixture.json'

// Vitest runs without `globals`, so RTL cannot register its own cleanup after each test.
afterEach(cleanup)

// The tool catalog the backend serves (GET /tools/catalog), as the app loads it before rendering.
// ToolCatalogFixtureTest keeps this copy equal to what the tool modules declare.
installToolCatalog(
  catalog.map((tool) => ({
    ...tool,
    name: { key: textId(tool.name), template: tool.name },
    hint: { key: textId(tool.hint), template: tool.hint },
  })),
)
