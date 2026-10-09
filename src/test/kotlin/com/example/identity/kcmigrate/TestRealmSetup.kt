package com.example.identity.kcmigrate

/** A complete setup for realm `demo`, shared by the migration runner tests. */
internal val TEST_REALM_SETUP = RealmSetup(
    realmName = "demo", realmDisplayName = "Demo", loginTheme = "orchestrator-keycloakify",
    browserClientId = "web", adminApiClientId = "admin", appTokenClientId = "app",
    browserRedirectUris = listOf("http://localhost/*"), orchestratorBaseUrl = "http://orchestrator:8080",
    publicOrchestratorBaseUrl = "http://localhost:8080", peerAuthIssuer = "kc", peerAuthAudience = "orch"
)
