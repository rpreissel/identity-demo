package com.example.identity.core.orchestrator.keycloak

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.core.orchestrator.dpop.DpopReplayProtectionService
import com.example.identity.core.orchestrator.dpop.inMemoryReplayRepository
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.util.Date
import java.util.UUID

/**
 * Pure unit test of [PeerAuthValidator] - no Spring context, no HTTP layer. [KeycloakJwkSource]
 * is mocked to hand back the test's own key directly, same reasoning as
 * [com.example.identity.core.orchestrator.dpop.DpopValidatorTest]: signature and claim checks run
 * without a real JWKS endpoint. A strict `mockk()` as key source proves that a check fires before
 * any JWKS lookup.
 */
class PeerAuthValidatorTest : BehaviorSpec({

    val issuer = "mock-keycloak"
    val audience = "identity-demo-orchestrator"
    val method = "PATCH"
    val url = "https://example.test/orchestrator/api/v1/kc/channels/abc"
    val kid = "test-key"

    fun validator(jwkSource: KeycloakJwkSource) = PeerAuthValidator(
        jwkSource = jwkSource,
        replayProtectionService = DpopReplayProtectionService(inMemoryReplayRepository(), clock = TEST_CLOCK),
        expectedIssuer = issuer,
        expectedAudience = audience,
        maxClockSkewSeconds = 30,
        maxAssertionAgeSeconds = 30,
        clock = TEST_CLOCK
    )

    fun jwkSourceReturning(key: ECKey) = mockk<KeycloakJwkSource> {
        every { find(kid) } returns key.toPublicJWK()
    }

    /** An assertion for [method] and [url]; each parameter breaks one part of it. `null` leaves the part out. */
    fun signAssertion(
        key: ECKey,
        htm: String = method,
        htu: String = url,
        issuedAt: Date = Date.from(TEST_NOW),
        jti: String? = UUID.randomUUID().toString(),
        iss: String? = issuer,
        aud: String? = audience,
        channelBinding: String? = "channel-binding-1",
        subject: String? = null,
        type: JOSEObjectType? = PeerAuthValidator.ASSERTION_TYPE,
        keyId: String? = kid,
        algorithm: JWSAlgorithm = JWSAlgorithm.ES256,
        signer: JWSSigner = ECDSASigner(key.toECPrivateKey())
    ): String {
        val header = JWSHeader.Builder(algorithm).type(type).keyID(keyId).build()
        val claimsBuilder = JWTClaimsSet.Builder()
            .issueTime(issuedAt)
            .claim("htm", htm)
            .claim("htu", htu)
        jti?.let { claimsBuilder.jwtID(it) }
        iss?.let { claimsBuilder.issuer(it) }
        aud?.let { claimsBuilder.audience(it) }
        channelBinding?.let { claimsBuilder.claim("channel_binding", it) }
        subject?.let { claimsBuilder.subject(it) }
        return SignedJWT(header, claimsBuilder.build()).apply { sign(signer) }.serialize()
    }

    fun rejection(result: Result<PeerAuthAssertion>): PeerAuthValidationException =
        shouldThrow<PeerAuthValidationException> { result.getOrThrow() }

    given("a validly signed, fresh assertion with a subject") {
        val key = ECKeyGenerator(Curve.P_256).generate()
        val jti = UUID.randomUUID().toString()
        val assertion = signAssertion(key, jti = jti, channelBinding = "channel-binding-42", subject = "kc-sub-42")
        val validator = validator(jwkSourceReturning(key))

        `when`("validating it") {
            val result = validator.validate(assertion, method, url)

            then("it reports the assertion's jti, channel binding and subject") {
                result.jti shouldBe jti
                result.channelBinding shouldBe "channel-binding-42"
                result.subject shouldBe "kc-sub-42"
            }
        }

        `when`("validating that same assertion a second time") {
            val result = runCatching { validator.validate(assertion, method, url) }

            then("it is rejected as a replay") {
                rejection(result).message shouldContain "replay"
            }
        }
    }

    given("no assertion at all") {
        `when`("validating a null assertion") {
            val result = runCatching { validator(mockk()).validate(null, method, url) }

            then("it is rejected as missing") {
                rejection(result).message shouldContain "Missing"
            }
        }

        `when`("validating a blank assertion") {
            val result = runCatching { validator(mockk()).validate("   ", method, url) }

            then("it is rejected as missing") {
                rejection(result).message shouldContain "Missing"
            }
        }
    }

    given("an assertion that isn't a well-formed JWT at all") {
        `when`("validating it") {
            val result = runCatching { validator(mockk()).validate("not-a-jwt", method, url) }

            then("it is rejected as an invalid format") {
                rejection(result).message shouldContain "format"
            }
        }
    }

    given("an assertion with a header problem") {
        val key = ECKeyGenerator(Curve.P_256).generate()

        `when`("it is RSA-signed with the right typ") {
            val rsaKey = RSAKeyGenerator(2048).keyID(kid).generate()
            val assertion = signAssertion(key, algorithm = JWSAlgorithm.RS256, signer = RSASSASigner(rsaKey.toPrivateKey()))
            val result = runCatching { validator(mockk()).validate(assertion, method, url) }

            then("it is refused before any JWKS lookup - the extension signs with ES256 only") {
                rejection(result).message shouldContain "algorithm"
            }
        }

        `when`("it carries no typ=peer-auth+jwt") {
            val result = runCatching { validator(mockk()).validate(signAssertion(key, type = null), method, url) }

            then("it is refused before any JWKS lookup - a JWT of another purpose is never read as one") {
                rejection(result).message shouldContain "typ"
            }
        }

        `when`("it carries no kid") {
            val result = runCatching { validator(mockk()).validate(signAssertion(key, keyId = null), method, url) }

            then("it is refused before any JWKS lookup") {
                rejection(result).message shouldContain "kid"
            }
        }
    }

    given("an assertion whose kid the key source doesn't know") {
        val assertion = signAssertion(ECKeyGenerator(Curve.P_256).generate())
        val jwkSource = mockk<KeycloakJwkSource> { every { find(kid) } returns null }

        `when`("validating it") {
            val result = runCatching { validator(jwkSource).validate(assertion, method, url) }

            then("it is rejected for the unknown key") {
                rejection(result).message shouldContain "Unknown peer-auth key id"
            }
        }
    }

    given("an assertion whose signature doesn't match the key the kid resolves to") {
        val assertion = signAssertion(ECKeyGenerator(Curve.P_256).generate())
        val otherKey = ECKeyGenerator(Curve.P_256).generate()

        `when`("validating it") {
            val result = runCatching { validator(jwkSourceReturning(otherKey)).validate(assertion, method, url) }

            then("the signature check fails") {
                rejection(result).message shouldContain "signature"
            }
        }
    }

    given("an assertion whose claims don't fit") {
        val key = ECKeyGenerator(Curve.P_256).generate()

        `when`("it names another issuer") {
            val result = runCatching { validator(jwkSourceReturning(key)).validate(signAssertion(key, iss = "someone-else"), method, url) }

            then("it is rejected for the issuer") {
                rejection(result).message shouldContain "issuer"
            }
        }

        `when`("it names another audience") {
            val result = runCatching { validator(jwkSourceReturning(key)).validate(signAssertion(key, aud = "some-other-service"), method, url) }

            then("it is rejected for the audience") {
                rejection(result).message shouldContain "audience"
            }
        }

        `when`("it names a different HTTP method than the request") {
            val result = runCatching { validator(jwkSourceReturning(key)).validate(signAssertion(key, htm = "GET"), method, url) }

            then("it is rejected for the method") {
                rejection(result).message shouldContain "htm"
            }
        }

        `when`("it names a different URL than the request") {
            val assertion = signAssertion(key, htu = "https://example.test/somewhere-else")
            val result = runCatching { validator(jwkSourceReturning(key)).validate(assertion, method, url) }

            then("it is rejected for the URL") {
                rejection(result).message shouldContain "htu"
            }
        }

        `when`("it carries no channel_binding") {
            val result = runCatching { validator(jwkSourceReturning(key)).validate(signAssertion(key, channelBinding = null), method, url) }

            then("it is rejected for the missing binding") {
                rejection(result).message shouldContain "channel_binding"
            }
        }

        `when`("it carries no jti") {
            val result = runCatching { validator(jwkSourceReturning(key)).validate(signAssertion(key, jti = null), method, url) }

            then("it is rejected for the missing jti") {
                rejection(result).message shouldContain "jti"
            }
        }
    }

    given("an assertion whose iat lies outside the window") {
        val key = ECKeyGenerator(Curve.P_256).generate()

        `when`("it was issued beyond the clock-skew allowance in the future") {
            val assertion = signAssertion(key, issuedAt = Date.from(TEST_NOW.plusSeconds(600)))
            val result = runCatching { validator(jwkSourceReturning(key)).validate(assertion, method, url) }

            then("it is rejected as issued in the future") {
                rejection(result).message shouldContain "future"
            }
        }

        `when`("it is older than maxAssertionAgeSeconds") {
            val assertion = signAssertion(key, issuedAt = Date.from(TEST_NOW.minusSeconds(600)))
            val result = runCatching { validator(jwkSourceReturning(key)).validate(assertion, method, url) }

            then("it is rejected as too old") {
                rejection(result).message shouldContain "too old"
            }
        }
    }

    given("an assertion issued just inside the clock-skew allowance") {
        val key = ECKeyGenerator(Curve.P_256).generate()
        val issuedAt = TEST_NOW.plusSeconds(29)
        val assertion = signAssertion(key, issuedAt = Date.from(issuedAt))

        `when`("validating it") {
            val result = validator(jwkSourceReturning(key)).validate(assertion, method, url)

            then("it is accepted with its own iat") {
                result.issuedAt shouldBe issuedAt
            }
        }
    }
})
