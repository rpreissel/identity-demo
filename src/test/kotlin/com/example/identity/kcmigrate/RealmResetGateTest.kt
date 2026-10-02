package com.example.identity.kcmigrate

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import jakarta.ws.rs.NotFoundException
import org.keycloak.admin.client.Keycloak
import org.keycloak.admin.client.resource.RealmResource
import org.keycloak.admin.client.resource.RealmsResource
import org.keycloak.representations.idm.RealmRepresentation

/**
 * Keycloak with realm `demo`, built with a different orchestratorBaseUrl than the one configured.
 * Removing the realm makes it unknown until it is created again.
 */
private class Fixture {
    private var removed = false
    private var recreated = false
    val realm = mockk<RealmResource>(relaxed = true).also {
        every { it.toRepresentation() } answers {
            when {
                recreated -> RealmRepresentation().apply { setRealm("demo") }
                removed -> throw NotFoundException()
                else -> RealmRepresentation().apply {
                    setRealm("demo")
                    attributes = mutableMapOf("kcmig_setup__orchestratorBaseUrl" to "http://old-host:8080")
                }
            }
        }
        every { it.remove() } answers { removed = true }
    }
    val realms = mockk<RealmsResource>(relaxed = true).also {
        every { it.create(any()) } answers { recreated = true }
    }
    val kc = mockk<Keycloak>().also {
        every { it.realm("demo") } returns realm
        every { it.realms() } returns realms
    }
}

/**
 * A changed setup value (or migration) needs the realm rebuilt - which throws away sessions, every `sub` and
 * every credential on the users. Only allowed in demo mode; otherwise the run stops with a message naming
 * why, and the realm is left alone.
 */
class RealmResetGateTest : BehaviorSpec({

    given("a setup value that changed since the realm was built, and no rebuild allowed (demo.mode=false)") {
        val f = Fixture()

        `when`("the migrations run") {
            val result = runCatching { MigrationRunner(f.kc, TEST_REALM_SETUP, emptyList(), allowRealmReset = false).up() }

            then("the run stops with the reason") {
                shouldThrow<RealmResetRefusedException> { result.getOrThrow() }.message shouldContain "orchestratorBaseUrl"
            }

            then("the realm is not removed") {
                verify(exactly = 0) { f.realm.remove() }
            }
        }
    }

    given("a setup value that changed since the realm was built, and the rebuild allowed (demo mode)") {
        val f = Fixture()

        `when`("the migrations run") {
            val result = runCatching { MigrationRunner(f.kc, TEST_REALM_SETUP, emptyList(), allowRealmReset = true).up() }

            then("the run completes") {
                result.getOrThrow()
            }

            then("the realm is removed and created anew") {
                verify(exactly = 1) { f.realm.remove() }
                verify(exactly = 1) { f.realms.create(match { it.realm == "demo" }) }
            }
        }
    }
})
