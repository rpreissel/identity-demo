package com.example.identity.kcmigrate

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.AuthenticationManagementResource
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.representations.idm.AuthenticationExecutionInfoRepresentation
import org.keycloak.representations.idm.AuthenticatorConfigRepresentation
import org.keycloak.representations.idm.RealmRepresentation

/**
 * Die Fristen, auf denen ADR-9 und ADR-43 aufbauen, setzt V5 ausdrücklich im Realm, und loa2 trägt
 * nur wenige Minuten. Das Zurückrollen stellt die vorherigen Werte wieder her.
 */
class RealmLifetimesMigrationTest : BehaviorSpec({

    val setup = RealmSetup(
        realmName = "demo", realmDisplayName = "Demo", loginTheme = "orchestrator",
        browserClientId = "web", adminApiClientId = "admin", appTokenClientId = "app",
        browserRedirectUris = listOf("http://localhost/*"), orchestratorBaseUrl = "http://orchestrator:8080",
        publicOrchestratorBaseUrl = "http://localhost:8080", peerAuthIssuer = "kc", peerAuthAudience = "orch"
    )
    val migration = checkNotNull(
        RealmLifetimesMigrationTest::class.java.getResource("/keycloak-migrations/V5__realm_lifetimes.kc.kts")
    ).readText().let { checkNotNull(MigrationFile.parse("V5__realm_lifetimes.kc.kts", it)) }

    /** Ein Realm mit Keycloaks Voreinstellungen und loa2 für zehn Stunden, wie V1 es anlegt. */
    class Realm {
        var rep = RealmRepresentation().apply {
            realm = "demo"
            accessTokenLifespan = 60
            ssoSessionIdleTimeout = 600
            ssoSessionMaxLifespan = 7200
            sslRequired = "none"
        }
        var loa2Condition = AuthenticatorConfigRepresentation().apply {
            alias = "orchestrator-loa-2-condition"
            config = mapOf("loa-condition-level" to "2", "loa-max-age" to "36000")
        }
        val runner: MigrationRunner

        init {
            val flows = mockk<AuthenticationManagementResource>()
            every { flows.getExecutions("orchestrator-loa-2") } returns listOf(
                AuthenticationExecutionInfoRepresentation().apply {
                    providerId = "conditional-level-of-authentication"
                    authenticationConfig = "cfg-loa-2"
                }
            )
            every { flows.getAuthenticatorConfig("cfg-loa-2") } answers { loa2Condition }
            every { flows.updateAuthenticatorConfig("cfg-loa-2", any()) } answers { loa2Condition = secondArg() }
            val realm = mockk<RealmResource>()
            every { realm.toRepresentation() } answers { rep }
            every { realm.update(any()) } answers { rep = firstArg() }
            every { realm.flows() } returns flows
            val kc = mockk<Keycloak>()
            every { kc.realm("demo") } returns realm
            runner = MigrationRunner(kc, setup, listOf(migration), allowRealmReset = false)
        }
    }

    given("ein Realm mit Keycloaks Voreinstellungen und loa2 für zehn Stunden") {
        `when`("V5 angewendet wird") {
            val realm = Realm()
            realm.runner.up()

            then("gelten 5 Minuten AccessToken, 30 Minuten Leerlauf, 10 Stunden Sitzung und https von außen") {
                realm.rep.accessTokenLifespan shouldBe 300
                realm.rep.ssoSessionIdleTimeout shouldBe 1800
                realm.rep.ssoSessionMaxLifespan shouldBe 36000
                realm.rep.sslRequired shouldBe "external"
            }
            then("trägt loa2 dreißig Minuten, die Stufe bleibt") {
                realm.loa2Condition.config["loa-max-age"] shouldBe "1800"
                realm.loa2Condition.config["loa-condition-level"] shouldBe "2"
            }
        }

        `when`("V5 angewendet und wieder zurückgerollt wird") {
            val realm = Realm()
            realm.runner.up()
            realm.runner.down("5")

            then("gelten wieder die vorherigen Werte") {
                realm.rep.accessTokenLifespan shouldBe 60
                realm.rep.ssoSessionIdleTimeout shouldBe 600
                realm.rep.ssoSessionMaxLifespan shouldBe 7200
                realm.rep.sslRequired shouldBe "none"
                realm.loa2Condition.config["loa-max-age"] shouldBe "36000"
            }
        }
    }
})
