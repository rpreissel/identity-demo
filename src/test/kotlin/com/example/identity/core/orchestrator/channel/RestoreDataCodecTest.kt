package com.example.identity.core.orchestrator.channel

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.FactorType
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.core.orchestrator.domain.AmrSource
import com.example.identity.core.orchestrator.domain.policy.AuthEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodEvidence
import com.example.identity.core.orchestrator.domain.policy.MethodName
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.MACSigner
import com.nimbusds.jose.util.Base64URL
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.time.Duration
import java.time.Instant
import java.util.Date

/**
 * Unit test of [RestoreDataCodec]: a token decodes back to what was encoded, and every problem
 * (expiry, signature, another session, garbage) yields null instead of an exception.
 */
class RestoreDataCodecTest : BehaviorSpec({

    val kcSessionId = "kc-session-1"
    val restoreData = RestoreData(
        accountId = 42L,
        evidence = AuthEvidence(
            listOf(
                MethodEvidence(
                    method = MethodName("sms"),
                    loa = AcrLevel.LOA1,
                    enrolledUnderAcr = AcrLevel.LOA1,
                    factorTypes = setOf(FactorType.POSSESSION),
                    source = AmrSource.ORCHESTRATOR,
                    amrSourceId = "auth-sms",
                    provenAt = TEST_NOW.minus(Duration.ofMinutes(20))
                ),
                MethodEvidence(
                    method = MethodName("password"),
                    loa = AcrLevel.LOA1,
                    factorTypes = setOf(FactorType.KNOWLEDGE),
                    source = AmrSource.KEYCLOAK,
                    amrSourceId = "auth-username-password-form",
                    provenAt = TEST_NOW.minus(Duration.ofMinutes(5))
                )
            )
        )
    )

    given("a token encoded for a Keycloak session") {
        val codec = RestoreDataCodec(clock = TEST_CLOCK)
        val token = codec.encode(restoreData, kcSessionId)

        `when`("decoding it for the same session") {
            val decoded = codec.decode(token, kcSessionId)

            then("it returns the encoded data, evidence and its age included") {
                decoded shouldBe restoreData
            }
        }

        `when`("decoding a proof encoded without its time") {
            val ageless = restoreData.copy(evidence = AuthEvidence(restoreData.evidence!!.factors.map { it.copy(provenAt = null) }))
            val decoded = codec.decode(codec.encode(ageless, kcSessionId), kcSessionId)

            then("it comes back as old as can be, never as just proven") {
                decoded?.evidence?.factors?.map { it.provenAt } shouldBe listOf(Instant.EPOCH, Instant.EPOCH)
            }
        }

        `when`("decoding it for another session") {
            val decoded = codec.decode(token, "kc-session-2")

            then("it returns null") {
                decoded.shouldBeNull()
            }
        }

        `when`("decoding it without a session") {
            val decoded = codec.decode(token, null)

            then("it returns null") {
                decoded.shouldBeNull()
            }
        }

        `when`("another codec, with its own secret, decodes it") {
            val decoded = RestoreDataCodec(clock = TEST_CLOCK).decode(token, kcSessionId)

            then("it returns null") {
                decoded.shouldBeNull()
            }
        }

        `when`("the payload was changed after signing") {
            val (header, _, signature) = token.split(".")
            val forgedClaims = JWTClaimsSet.Builder()
                .subject(kcSessionId)
                .expirationTime(Date.from(TEST_NOW.plus(Duration.ofHours(1))))
                .claim("accountId", 999L)
                .build()
            val forged = "$header.${Base64URL.encode(forgedClaims.toString())}.$signature"
            val decoded = codec.decode(forged, kcSessionId)

            then("it returns null") {
                decoded.shouldBeNull()
            }
        }
    }

    given("a token with an account and no evidence") {
        val codec = RestoreDataCodec(clock = TEST_CLOCK)
        val accountOnly = RestoreData(accountId = 42L)
        val token = codec.encode(accountOnly, kcSessionId)

        `when`("decoding it") {
            val decoded = codec.decode(token, kcSessionId)

            then("it returns the account without evidence") {
                decoded shouldBe accountOnly
            }
        }
    }

    given("a correctly signed token whose expiry has passed") {
        // A validity already over when the token is made: expired from the start.
        val codec = RestoreDataCodec(ttl = Duration.ofSeconds(-1), clock = TEST_CLOCK)
        val expired = codec.encode(RestoreData(accountId = 42L), kcSessionId)

        `when`("decoding it") {
            val decoded = codec.decode(expired, kcSessionId)

            then("it returns null") {
                decoded.shouldBeNull()
            }
        }
    }

    given("a string that is no JWT") {
        val codec = RestoreDataCodec(clock = TEST_CLOCK)

        `when`("decoding it") {
            val decoded = codec.decode("not-a-token", kcSessionId)

            then("it returns null instead of throwing") {
                decoded.shouldBeNull()
            }
        }
    }
})
