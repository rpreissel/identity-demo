import { defineConfig } from '@playwright/test'
import { fileURLToPath } from 'url'
import { dirname } from 'path'

const __dirname = dirname(fileURLToPath(import.meta.url))

/**
 * The real end-to-end suite against the Spring Boot backend + built frontend, no mocks: real DPoP
 * WebCrypto proofs in a real browser, real HTTP, real server state. Keep it small; anything
 * expressible with a mocked api.ts belongs in *.test.tsx.
 */
// Not the dev port (8080, application.yml): this suite owns its server, and a dev instance
// running there serves the file-based dev DB. Not 8090/8091 either: the local OpenShift test pod
// (openshift/local-up.sh) publishes the orchestrator and Keycloak there.
const E2E_PORT = 8095
/** identity.policy.self-service-max-age of the suite's own backend; also read by fresh-proof.spec.ts. */
export const SELF_SERVICE_MAX_AGE_SECONDS = 10

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  fullyParallel: false,
  workers: 1,
  use: {
    baseURL: `http://localhost:${E2E_PORT}`,
    // The suite names texts by template and reads the German wording (e2e/texts.ts) - so German it is.
    locale: 'de-DE',
    trace: 'retain-on-failure',
  },
  webServer: {
    // A fresh in-memory DB per run is required: the seed data (V2__testdata.sql) has only 3 persons,
    // and a KVNR provisioned by an earlier run resolves to LOGIN instead of REGISTRATION.
    // Hence reuseExistingServer: false and a port of our own: reusing whatever listens would
    // silently ignore the SPRING_DATASOURCE_URL below and hit the dev file DB.
    // A short limit for the fresh proof, so fresh-proof.spec.ts can outwait it; every other spec
    // acts within seconds of its registration and never meets it.
    command: `./gradlew bootRun --args='--server.port=${E2E_PORT} --management.server.port=0 --identity.policy.self-service-max-age=PT${SELF_SERVICE_MAX_AGE_SECONDS}S'`,
    cwd: dirname(__dirname),
    url: `http://localhost:${E2E_PORT}`,
    reuseExistingServer: false,
    timeout: 120_000,
    env: {
      SPRING_DATASOURCE_URL: `jdbc:h2:mem:e2e-${Date.now()};DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE`,
    },
  },
})
