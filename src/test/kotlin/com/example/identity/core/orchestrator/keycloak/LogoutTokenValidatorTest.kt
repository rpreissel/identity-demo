package com.example.identity.core.orchestrator.keycloak

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.core.orchestrator.LogoutTokens
import com.example.identity.core.orchestrator.dpop.DpopFailure
import com.example.identity.core.orchestrator.dpop.DpopReplayProtectionService
import com.example.identity.core.orchestrator.dpop.DpopValidationException
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.PlainJWT
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.util.Date

/**
 * Every rule a `logout_token` must pass (OIDC Back-Channel Logout 1.0, section 2.6), each broken
 * once. A token that breaks one is refused as a whole, so a forged or misdirected one ends nothing.
 */
class LogoutTokenValidatorTest : BehaviorSpec({

    fun validator(
        replay: DpopReplayProtectionService = mockk(relaxed = true),
        key: RSAKey = LogoutTokens.publicKey,
        issuer: String = LogoutTokens.ISSUER,
    ) = LogoutTokenValidator(
        jwkSource = mockk { every { find(any()) } returns null; every { find(LogoutTokens.KID) } returns key },
        replayProtectionService = replay,
        clock = TEST_CLOCK,
        expectedIssuer = issuer,
        audiences = "${LogoutTokens.WEB_CLIENT},${LogoutTokens.APP_CLIENT}",
        maxClockSkewSeconds = 30,
        maxAgeSeconds = 120,
    )

    fun token(sid: String? = "kc-session-1", customize: JWTClaimsSet.Builder.() -> Unit = {}) =
        LogoutTokens.signed(sid, now = TEST_NOW, customize = customize)

    given("a logout_token as Keycloak sends it") {
        val replay = mockk<DpopReplayProtectionService>(relaxed = true)

        `when`("it is validated") {
            val result = validator(replay).validate(token())

            then("it names the session and the client it was meant for") {
                result shouldBe LogoutToken(sid = "kc-session-1", audience = LogoutTokens.WEB_CLIENT)
            }
            then("its jti is spent, under its own prefix") {
                verify { replay.validateAndStore("bcl:${LogoutTokens.KID}", any(), any()) }
            }
        }
    }

    given("a logout_token for the App's client") {
        `when`("it is validated") {
            val result = validator().validate(LogoutTokens.signed("kc-session-2", audience = LogoutTokens.APP_CLIENT, now = TEST_NOW))

            then("it is accepted as well") {
                result.audience shouldBe LogoutTokens.APP_CLIENT
            }
        }
    }

    mapOf(
        "no token at all" to { null },
        "an unsigned token (alg=none)" to { PlainJWT(JWTClaimsSet.Builder().claim("sid", "kc-session-1").build()).serialize() },
        "a token of another type (typ=JWT)" to { LogoutTokens.signed("kc-session-1", now = TEST_NOW, header = { type(JOSEObjectType.JWT) }) },
        "a token signed with a key the realm never published" to { LogoutTokens.signed("kc-session-1", signingKey = LogoutTokens.foreignKey(), now = TEST_NOW) },
        "a token naming an unknown key id" to { LogoutTokens.signed("kc-session-1", now = TEST_NOW, header = { keyID("other") }) },
        "a token signed with a shared secret (HS256)" to {
            val header = JWSHeader.Builder(JWSAlgorithm.HS256).type(LogoutTokenValidator.LOGOUT_TOKEN_TYPE).keyID(LogoutTokens.KID).build()
            SignedJWT(header, JWTClaimsSet.Builder().claim("sid", "kc-session-1").build()).apply { sign(MACSigner(ByteArray(32))) }.serialize()
        },
        "a token from another issuer" to { token { issuer("https://evil.test/realms/Demo") } },
        "a token for another client" to { token { audience("some-other-client") } },
        "a token without sid" to { token(sid = null) },
        "a token with a nonce, i.e. an ID token" to { token { claim("nonce", "n") } },
        "a token without the logout event" to { token { claim("events", mapOf("urn:other" to emptyMap<String, Any>())) } },
        "a token without events" to { token { claim("events", null) } },
        "a token issued in the future" to { token { issueTime(Date.from(TEST_NOW.plusSeconds(300))) } },
        "a token issued too long ago" to { token { issueTime(Date.from(TEST_NOW.minusSeconds(600))) } },
        "an expired token" to { token { expirationTime(Date.from(TEST_NOW.minusSeconds(60))) } },
        "a token without exp" to { token { expirationTime(null) } },
        "a token without jti" to { token { jwtID(null) } },
    ).forEach { (case, makeToken) ->
        given(case) {
            `when`("it is validated") {
                val result = runCatching { validator().validate(makeToken()) }

                then("it is refused") {
                    shouldThrow<LogoutTokenException> { result.getOrThrow() }
                }
            }
        }
    }

    given("a key the realm publishes for PS256 only") {
        val ps256Key = RSAKey.Builder(LogoutTokens.publicKey).algorithm(JWSAlgorithm.PS256).build()

        `when`("a token signed RS256 with it arrives") {
            val result = runCatching { validator(key = ps256Key).validate(token()) }

            then("it is refused: a key vouches only for its own algorithm") {
                shouldThrow<LogoutTokenException> { result.getOrThrow() }
            }
        }
    }

    given("a token that fails a check") {
        val replay = mockk<DpopReplayProtectionService>(relaxed = true)

        `when`("it is validated") {
            runCatching { validator(replay).validate(token { issuer("https://evil.test/realms/Demo") }) }

            then("nothing is written: a refused token costs no row") {
                verify(exactly = 0) { replay.validateAndStore(any(), any(), any()) }
            }
        }
    }

    given("a refused token") {
        `when`("its reason is read") {
            val result = runCatching { validator().validate(LogoutTokens.signed("kc-session-1", now = TEST_NOW, header = { keyID("evil\ninjected") })) }

            then("the reason names the rule, never a value from the token") {
                (result.exceptionOrNull()?.message ?: "") shouldNotContain "evil"
            }
        }
    }

    given("a key the realm publishes for encryption, not for signatures") {
        val encryptionKey = RSAKey.Builder(LogoutTokens.publicKey).keyUse(KeyUse.ENCRYPTION).build()

        `when`("a token names it") {
            val result = runCatching { validator(key = encryptionKey).validate(token()) }

            then("it is refused") {
                shouldThrow<LogoutTokenException> { result.getOrThrow() }
            }
        }
    }

    given("a logout_token whose jti was already spent") {
        val replay = mockk<DpopReplayProtectionService> {
            every { validateAndStore(any(), any(), any()) } throws DpopValidationException(DpopFailure.REPLAY)
        }

        `when`("it arrives again") {
            val result = runCatching { validator(replay).validate(token()) }

            then("it is refused") {
                shouldThrow<LogoutTokenException> { result.getOrThrow() }
            }
        }
    }

    given("no realm configured (blank issuer)") {
        `when`("a token that would otherwise pass arrives") {
            val result = runCatching { validator(issuer = "").validate(token()) }

            then("it is refused") {
                shouldThrow<LogoutTokenException> { result.getOrThrow() }
            }
        }
    }
})
