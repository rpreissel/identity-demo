import { expect, type APIRequestContext } from '@playwright/test'

/**
 * The orchestrator's admin switches the Keycloak suites set before they look at a login page.
 * ORCHESTRATOR_URL, ADMIN_USER and ADMIN_PASSWORD point elsewhere (playwright.keycloak.config.ts).
 */

export const ORCHESTRATOR = process.env.ORCHESTRATOR_URL ?? 'http://localhost:8080'
const ADMIN = { username: process.env.ADMIN_USER ?? 'admin', password: process.env.ADMIN_PASSWORD ?? 'admin' }
export const adminHeaders = { Authorization: `Basic ${Buffer.from(`${ADMIN.username}:${ADMIN.password}`).toString('base64')}` }

export type Theme = 'FREEMARKER' | 'KEYCLOAKIFY'
export const THEMES = ['FREEMARKER', 'KEYCLOAKIFY'] as const satisfies readonly Theme[]
/** What the note in the dark band of a login page names as its technique. */
export const MADE_WITH: Record<Theme, string> = { FREEMARKER: 'FreeMarker', KEYCLOAKIFY: 'Keycloakify' }

type Loa1Login = 'KEYCLOAK_PASSWORD' | 'ORCHESTRATOR'

export async function switchTheme(request: APIRequestContext, theme: Theme) {
  const put = await request.put(`${ORCHESTRATOR}/orchestrator/admin/login-theme`, { headers: adminHeaders, data: { theme } })
  expect(put.status()).toBe(200)
  const get = await request.get(`${ORCHESTRATOR}/orchestrator/admin/login-theme`, { headers: adminHeaders })
  expect(await get.json()).toEqual({ theme })
}

/** What loa1 asks for (docs/adr/ADR-042-loa1-anmeldung-umschalten.md), realm-wide like the theme. */
export async function switchLoa1Login(request: APIRequestContext, login: Loa1Login) {
  const put = await request.put(`${ORCHESTRATOR}/orchestrator/admin/loa1-login`, { headers: adminHeaders, data: { login } })
  expect(put.status()).toBe(200)
  const get = await request.get(`${ORCHESTRATOR}/orchestrator/admin/loa1-login`, { headers: adminHeaders })
  expect(await get.json()).toEqual({ login })
}
