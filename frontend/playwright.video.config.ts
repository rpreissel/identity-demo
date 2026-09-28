import { defineConfig } from '@playwright/test'

/** Screen recording of the demo tasks against the compose stack (orchestrator 8080, Keycloak 8543) - not part of the test suites. */
export default defineConfig({
  testDir: './e2e-video',
  outputDir: './test-results-video',
  timeout: 600_000,
  workers: 1,
  retries: 0,
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:8080',
    ignoreHTTPSErrors: true,
    locale: 'de-DE',
    viewport: { width: 1600, height: 1100 },
    video: { mode: 'on', size: { width: 1600, height: 1100 } },
    launchOptions: { slowMo: 600 },
  },
})
