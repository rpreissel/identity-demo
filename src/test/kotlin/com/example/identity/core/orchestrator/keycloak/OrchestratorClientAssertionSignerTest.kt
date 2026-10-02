package com.example.identity.core.orchestrator.keycloak

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.nimbusds.jose.crypto.ECDSAVerifier
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import java.util.Optional

private const val ADMIN_CLIENT = "orchestrator-admin"
private const val APP_TOKEN_CLIENT = "orchestrator-app-token"
private const val REALM = "https://kc/realms/Demo"

/**
 * Pure unit test of [OrchestratorClientAssertionSigner]: every Keycloak client signs with its own key, so a
 * forged assertion for the rights-free token client is no assertion for the admin or migration client.
 */
class OrchestratorClientAssertionSignerTest : BehaviorSpec({

    /** A signer over an in-memory key store that refuses a second key for the same purpose. */
    fun signer(): OrchestratorClientAssertionSigner {
        val stored = mutableMapOf<String, NodeSigningKey>()
        val repository = mockk<NodeSigningKeyRepository>()
        every { repository.findById(any()) } answers { Optional.ofNullable(stored[firstArg()]) }
        every { repository.insert(any(), any(), any(), any()) } answers {
            val purpose = firstArg<String>()
            check(purpose !in stored) { "duplicate purpose $purpose" }
            stored[purpose] = NodeSigningKey(purpose = purpose, publicKeyJwk = secondArg(), privateKeyJwk = thirdArg(), createdAt = TEST_NOW)
        }
        return OrchestratorClientAssertionSigner(repository, ADMIN_CLIENT, APP_TOKEN_CLIENT, clock = TEST_CLOCK)
    }

    given("the three clients this node represents") {
        val signer = signer()

        `when`("reading each client's public key") {
            val admin = signer.publicKeyOf(ADMIN_CLIENT)!!
            val appToken = signer.publicKeyOf(APP_TOKEN_CLIENT)!!
            val migration = signer.publicKeyOf(KeycloakMigrationToken.CLIENT_ID)!!

            then("each has a key of its own") {
                admin.computeThumbprint() shouldNotBe appToken.computeThumbprint()
                admin.computeThumbprint() shouldNotBe migration.computeThumbprint()
                appToken.computeThumbprint() shouldNotBe migration.computeThumbprint()
            }
        }

        `when`("reading the admin client's key a second time") {
            val first = signer.publicKeyOf(ADMIN_CLIENT)!!
            val second = signer.publicKeyOf(ADMIN_CLIENT)!!

            then("the key stays the same") {
                second.computeThumbprint() shouldBe first.computeThumbprint()
            }
        }

        `when`("signing an assertion for the app-token client") {
            val jwt = SignedJWT.parse(signer.assertionFor(APP_TOKEN_CLIENT, REALM))

            then("it verifies only against that client's own key") {
                jwt.verify(ECDSAVerifier(signer.publicKeyOf(APP_TOKEN_CLIENT)!!)) shouldBe true
                jwt.verify(ECDSAVerifier(signer.publicKeyOf(ADMIN_CLIENT)!!)) shouldBe false
                jwt.verify(ECDSAVerifier(signer.publicKeyOf(KeycloakMigrationToken.CLIENT_ID)!!)) shouldBe false
            }
            then("it names the client as issuer and subject, and the realm as its one audience") {
                jwt.jwtClaimsSet.issuer shouldBe APP_TOKEN_CLIENT
                jwt.jwtClaimsSet.subject shouldBe APP_TOKEN_CLIENT
                jwt.jwtClaimsSet.audience shouldBe listOf(REALM)
            }
            then("it is valid for 60 seconds from now (ADR-9)") {
                jwt.jwtClaimsSet.issueTime.toInstant() shouldBe TEST_NOW
                jwt.jwtClaimsSet.expirationTime.toInstant() shouldBe TEST_NOW.plusSeconds(60)
            }
        }

        `when`("signing two assertions for the same client") {
            val first = SignedJWT.parse(signer.assertionFor(APP_TOKEN_CLIENT, REALM)).jwtClaimsSet.jwtid
            val second = SignedJWT.parse(signer.assertionFor(APP_TOKEN_CLIENT, REALM)).jwtClaimsSet.jwtid

            then("each carries a fresh jti, so Keycloak can refuse a replay (ADR-9)") {
                first shouldNotBe null
                second shouldNotBe first
            }
        }
    }

    given("a client this node does not represent") {
        val signer = signer()

        `when`("reading its public key") {
            val key = signer.publicKeyOf("some-other-client")

            then("it has none") {
                key.shouldBeNull()
            }
        }

        `when`("signing an assertion for it") {
            val result = runCatching { signer.assertionFor("some-other-client", REALM) }

            then("it gets none") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }
})
