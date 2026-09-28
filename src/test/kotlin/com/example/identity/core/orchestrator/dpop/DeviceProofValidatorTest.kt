package com.example.identity.core.orchestrator.dpop

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.device.UserVerification
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.mock.web.MockHttpServletRequest
import java.net.URI
import java.util.Date
import java.util.UUID

/**
 * Unit test without Spring. JwkThumbprintService and DpopReplayProtectionService are real, so replay
 * detection is actually exercised. Only the repository is stubbed by [inMemoryReplayRepository].
 */
class DeviceProofValidatorTest : BehaviorSpec({

    val validator = DeviceProofValidator(
        jwkThumbprintService = JwkThumbprintService(),
        replayProtectionService = DpopReplayProtectionService(inMemoryReplayRepository(), clock = TEST_CLOCK),
        maxClockSkewSeconds = 30,
        maxProofAgeSeconds = 60,
        clock = TEST_CLOCK
    )

    val url = "https://example.test/orchestrator/api/v1/tools/${UUID.randomUUID()}/enroll-device"
    /** The request the proof must name: same method and target as [url]. */
    val request = MockHttpServletRequest("PATCH", URI(url).path).apply {
        scheme = "https"
        serverName = "example.test"
        serverPort = 443
    }

    fun signProof(key: ECKey, userVerification: String, issuedAt: Date = Date.from(TEST_NOW), jti: String = UUID.randomUUID().toString()): String {
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("device-proof+jwt"))
            .jwk(key.toPublicJWK())
            .build()
        val claims = JWTClaimsSet.Builder()
            .jwtID(jti)
            .issueTime(issuedAt)
            .claim("htm", "PATCH")
            .claim("htu", url)
            .claim("userVerification", userVerification)
            .build()
        val signedJWT = SignedJWT(header, claims)
        signedJWT.sign(ECDSASigner(key.toECPrivateKey()))
        return signedJWT.serialize()
    }

    given("a validly signed, fresh device proof") {
        `when`("validating it") {
            then("it succeeds and reports the userVerification the proof carried") {
                val key = ECKeyGenerator(Curve.P_256).generate()
                val proof = signProof(key, "biometric")

                val result = validator.validate(proof, request)

                result.userVerification shouldBe UserVerification.BIOMETRIC
                result.publicKey.thumbprint shouldBe JwkThumbprintService().computeThumbprint(key.toPublicJWK())
            }
        }

        `when`("validating that same proof a second time") {
            then("it is rejected as a replay") {
                val key = ECKeyGenerator(Curve.P_256).generate()
                val proof = signProof(key, "biometric")

                validator.validate(proof, request)

                shouldThrow<DpopValidationException> {
                    validator.validate(proof, request)
                }
            }
        }
    }

    given("a device proof signed more than maxProofAgeSeconds ago") {
        val key = ECKeyGenerator(Curve.P_256).generate()
        val staleProof = signProof(key, "pin", issuedAt = Date.from(TEST_NOW.minusSeconds(600)))

        `when`("validating it") {
            then("it is rejected as expired") {
                shouldThrow<DpopValidationException> {
                    validator.validate(staleProof, request)
                }
            }
        }
    }

    given("a proof whose header carries a different key than the one that signed it") {
        val headerKey = ECKeyGenerator(Curve.P_256).generate()
        val signingKey = ECKeyGenerator(Curve.P_256).generate()
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("device-proof+jwt"))
            .jwk(headerKey.toPublicJWK())
            .build()
        val claims = JWTClaimsSet.Builder()
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(TEST_NOW))
            .claim("htm", "PATCH")
            .claim("htu", url)
            .claim("userVerification", "pin")
            .build()
        val signedJWT = SignedJWT(header, claims)
        signedJWT.sign(ECDSASigner(signingKey.toECPrivateKey()))
        val tamperedProof = signedJWT.serialize()

        `when`("validating it") {
            then("the signature check fails") {
                shouldThrow<DpopValidationException> {
                    validator.validate(tamperedProof, request)
                }
            }
        }
    }

    given("a device proof with an unsupported userVerification value") {
        val key = ECKeyGenerator(Curve.P_256).generate()
        val proof = signProof(key, "voiceprint")

        `when`("validating it") {
            then("it is rejected") {
                shouldThrow<DpopValidationException> {
                    validator.validate(proof, request)
                }
            }
        }
    }

    given("no device proof at all") {
        `when`("validating a null proof") {
            then("it is rejected as missing") {
                shouldThrow<DpopValidationException> {
                    validator.validate(null, request)
                }
            }
        }
    }
})

/**
 * The smallest stub that keeps replay detection real: a map plus the primary-key violation
 * (DataIntegrityViolationException). Only `insert` is stubbed, because the check is the insert.
 * `saveAndFlush` merges instead of inserting, so it must not be faked as one; `DpopReplayProtectionDbTest`
 * covers the real repository.
 */
private fun inMemoryReplayRepository(): DpopProofReplayRepository {
    val seen = mutableSetOf<String>()
    val repository = mockk<DpopProofReplayRepository>()
    every { repository.insert(any(), any()) } answers {
        val proofHash = firstArg<String>()
        if (!seen.add(proofHash)) {
            throw DataIntegrityViolationException("duplicate proof_hash $proofHash")
        }
    }
    return repository
}
