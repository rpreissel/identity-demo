package com.example.identity.kcmigrate

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import jakarta.ws.rs.core.Response
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.ClientResource
import org.keycloak.admin.client.resource.ClientsResource
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.ClientRepresentation
import org.keycloak.representations.idm.RealmRepresentation

/**
 * Der Client, mit dem der Orchestrator App-Tokens holt, hat keine Rechte über den Account-Token-Grant
 * hinaus und weist sich nur per signierter Assertion aus (ADR-9, ADR-25). V1 und V4 legen ihn so an.
 */
class AppTokenClientMigrationTest : BehaviorSpec({

    fun migration(name: String): MigrationFile = checkNotNull(
        AppTokenClientMigrationTest::class.java.getResource("/keycloak-migrations/$name")
    ).readText().let { checkNotNull(MigrationFile.parse(name, it)) }

    val v1 = migration("V1__realm.kc.kts")
    val v4 = migration("V4__account_token_grant_client.kc.kts")

    /** Ein Realm, in dem von V1 alles außer dem Anlegen des App-Token-Clients schon erledigt ist. */
    class Realm {
        var rep = RealmRepresentation().apply { realm = "demo" }
        var client: ClientRepresentation? = null
        val runner: MigrationRunner

        init {
            val steps = Regex("""(?m)^step\("([^"]+)"\)""").findAll(v1.text).map { it.groupValues[1] }.toList()
            val appTokenStep = steps.indexOf("orchestrator-app-token client anlegen")
            check(appTokenStep >= 0) { "V1 hat keinen Schritt für den App-Token-Client mehr" }
            rep.attributes = steps.indices.filter { it != appTokenStep }
                .associate { v1.stepDoneAttrKey(it) to "erledigt" }

            val clientResource = mockk<ClientResource>()
            every { clientResource.toRepresentation() } answers { checkNotNull(client) }
            every { clientResource.update(any()) } answers { client = firstArg() }
            val clients = mockk<ClientsResource>()
            every { clients.create(any()) } answers {
                client = firstArg<ClientRepresentation>().apply { id = "db-app" }
                mockk<Response>(relaxed = true)
            }
            every { clients.findByClientId(TEST_REALM_SETUP.appTokenClientId) } answers { listOfNotNull(client) }
            every { clients.get("db-app") } returns clientResource
            val realm = mockk<RealmResource>()
            every { realm.toRepresentation() } answers { rep }
            every { realm.update(any()) } answers { rep = firstArg() }
            every { realm.clients() } returns clients
            val kc = mockk<Keycloak>()
            every { kc.realm("demo") } returns realm
            runner = MigrationRunner(kc, TEST_REALM_SETUP, listOf(v1, v4), allowRealmReset = false)
        }
    }

    given("ein Realm, dem nur noch der App-Token-Client fehlt") {
        `when`("V1 und V4 angewendet werden") {
            val realm = Realm()
            realm.runner.up()
            val client = checkNotNull(realm.client)

            then("ist er vertraulich, ohne Browser-Login, ohne Passwort-Grant und ohne eigenes Konto") {
                client.clientId shouldBe TEST_REALM_SETUP.appTokenClientId
                client.isPublicClient shouldBe false
                client.isStandardFlowEnabled shouldBe false
                client.isDirectAccessGrantsEnabled shouldBe false
                client.isServiceAccountsEnabled shouldBe false
            }
            then("weist er sich per signierter Assertion aus, deren Schlüssel der Orchestrator veröffentlicht") {
                client.clientAuthenticatorType shouldBe "client-jwt"
                client.attributes["token.endpoint.auth.signing.alg"] shouldBe "ES256"
                client.attributes["jwks.url"] shouldBe
                    "${TEST_REALM_SETUP.orchestratorBaseUrl}/orchestrator/api/v1/kc/client-jwks/" +
                    "${TEST_REALM_SETUP.appTokenClientId}/.well-known/jwks.json"
            }
            then("darf er den Account-Token-Grant aufrufen") {
                client.attributes["identity-demo.account-token-grant"] shouldBe "true"
            }
        }
    }
})
