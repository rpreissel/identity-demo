package com.example.identity.kcmigrate

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.AuthenticationManagementResource
import org.keycloak.admin.client.resource.ClientResource
import org.keycloak.admin.client.resource.ClientScopesResource
import org.keycloak.admin.client.resource.ClientsResource
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation
import org.keycloak.representations.idm.ClientRepresentation
import org.keycloak.representations.idm.ClientScopeRepresentation
import org.keycloak.representations.idm.RealmRepresentation
import org.keycloak.representations.idm.RequiredActionProviderRepresentation

/**
 * Keycloaks Voreinstellungen führen am Orchestrator vorbei: eigene Required Actions, der Browser-Flow
 * des Realms, Direct Grants, die Account-Konsole und Offline-Tokens. V7 schließt sie
 * (docs/review-2026-10-03-sicherheitsaudit.md SA-2, SA-3, SA-7).
 */
class LockedDownDefaultsMigrationTest : BehaviorSpec({

    val v7 = checkNotNull(
        LockedDownDefaultsMigrationTest::class.java.getResource("/keycloak-migrations/V7__locked_down_defaults.kc.kts")
    ).readText().let { checkNotNull(MigrationFile.parse("V7__locked_down_defaults.kc.kts", it)) }

    /** Ein Realm mit Keycloaks Voreinstellungen und den drei Clients des Projekts. */
    class Realm {
        var rep = RealmRepresentation().apply {
            realm = "demo"
            browserFlow = "browser"
            directGrantFlow = "direct grant"
            isResetPasswordAllowed = true
        }
        val requiredActions = listOf("UPDATE_PASSWORD", "CONFIGURE_TOTP", "VERIFY_EMAIL", "orchestrator-manage-methods")
            .associateWith { alias -> RequiredActionProviderRepresentation().apply { this.alias = alias; isEnabled = true } }
            .toMutableMap()
        val createdFlows = mutableListOf<String>()
        val clients = (listOf("admin-cli", "account", "account-console") +
            listOf(TEST_REALM_SETUP.browserClientId, TEST_REALM_SETUP.adminApiClientId, TEST_REALM_SETUP.appTokenClientId))
            .associateWith { id ->
                ClientRepresentation().apply {
                    clientId = id
                    this.id = "db-$id"
                    isEnabled = true
                    isDirectAccessGrantsEnabled = id == "admin-cli"
                }
            }.toMutableMap()
        val offlineScope = ClientScopeRepresentation().apply { id = "scope-offline"; name = "offline_access" }
        val optionalScopes = clients.keys.associateWith { mutableListOf(offlineScope) }
        val runner: MigrationRunner

        init {
            val flows = mockk<AuthenticationManagementResource>(relaxed = true)
            every { flows.requiredActions } answers { requiredActions.values.map { it } }
            every { flows.getRequiredAction(any()) } answers { requiredActions.getValue(firstArg()) }
            every { flows.updateRequiredAction(any(), any()) } answers { requiredActions[firstArg()] = secondArg() }
            every { flows.createFlow(any()) } answers { createdFlows += firstArg<org.keycloak.representations.idm.AuthenticationFlowRepresentation>().alias; mockk(relaxed = true) }
            every { flows.getExecutions(any()) } returns listOf(AuthenticationExecutionInfoRepresentation().apply {
                id = "deny"
                providerId = "deny-access-authenticator"
            })

            val clientsResource = mockk<ClientsResource>()
            every { clientsResource.findByClientId(any()) } answers { listOfNotNull(clients[firstArg()]) }
            clients.forEach { (clientId, client) ->
                val resource = mockk<ClientResource>(relaxed = true)
                every { resource.update(any()) } answers { clients[clientId] = firstArg() }
                every { resource.optionalClientScopes } answers { optionalScopes.getValue(clientId).toList() }
                every { resource.removeOptionalClientScope(any()) } answers { optionalScopes.getValue(clientId).removeIf { it.id == firstArg() } }
                every { clientsResource.get(client.id) } returns resource
            }
            val scopes = mockk<ClientScopesResource>()
            every { scopes.findAll() } returns listOf(offlineScope)

            val realm = mockk<RealmResource>()
            every { realm.toRepresentation() } answers { rep }
            every { realm.update(any()) } answers { rep = firstArg() }
            every { realm.flows() } returns flows
            every { realm.clients() } returns clientsResource
            every { realm.clientScopes() } returns scopes
            val kc = mockk<Keycloak>()
            every { kc.realm("demo") } returns realm
            runner = MigrationRunner(kc, TEST_REALM_SETUP, listOf(v7), allowRealmReset = false)
        }
    }

    given("ein Realm mit Keycloaks Voreinstellungen") {
        `when`("V7 angewendet wird") {
            val realm = Realm()
            realm.runner.up()

            then("ist nur noch die eigene Required Action aktiv, auch nicht 'Passwort ändern'") {
                realm.requiredActions.filterValues { it.isEnabled }.keys shouldBe setOf("orchestrator-manage-methods")
            }
            then("meldet der Browser-Flow des Realms über den Orchestrator an, und Direct Grants lehnt ein eigener Flow ab") {
                realm.rep.browserFlow shouldBe "orchestrator-browser"
                realm.rep.directGrantFlow shouldBe "orchestrator-deny-direct-grant"
                realm.createdFlows shouldBe listOf("orchestrator-deny-direct-grant")
            }
            then("gibt es kein 'Passwort vergessen'") {
                realm.rep.isResetPasswordAllowed shouldBe false
            }
            then("nimmt admin-cli keine Passwort-Grants an, und die Account-Konsole ist aus") {
                realm.clients.getValue("admin-cli").isDirectAccessGrantsEnabled shouldBe false
                realm.clients.getValue("account").isEnabled shouldBe false
                realm.clients.getValue("account-console").isEnabled shouldBe false
            }
            then("kann keiner der Projekt-Clients ein Offline-Token anfordern") {
                listOf(TEST_REALM_SETUP.browserClientId, TEST_REALM_SETUP.adminApiClientId, TEST_REALM_SETUP.appTokenClientId)
                    .forEach { realm.optionalScopes.getValue(it).map { scope -> scope.name } shouldBe emptyList() }
            }
        }
    }
})
