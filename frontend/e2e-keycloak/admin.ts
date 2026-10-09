/**
 * The orchestrator's admin access the Keycloak suites use, e.g. for the demo reset.
 * ORCHESTRATOR_URL, ADMIN_USER and ADMIN_PASSWORD point elsewhere (playwright.keycloak.config.ts).
 */

export const ORCHESTRATOR = process.env.ORCHESTRATOR_URL ?? 'http://localhost:8080'
const ADMIN = { username: process.env.ADMIN_USER ?? 'admin', password: process.env.ADMIN_PASSWORD ?? 'admin' }
export const adminHeaders = { Authorization: `Basic ${Buffer.from(`${ADMIN.username}:${ADMIN.password}`).toString('base64')}` }
