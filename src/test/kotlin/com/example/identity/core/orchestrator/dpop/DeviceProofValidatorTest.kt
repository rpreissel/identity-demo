package com.example.identity.core.orchestrator.dpop

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.device.UserVerification
import com.example.identity.contract.tool_api.device.VerifiedDeviceProof
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
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

    val url = "https://example.test/tools/api/enroll-device/v1/${UUID.randomUUID()}"
    /** The request the proof must name: same method and target as [url]. */
    val request = MockHttpServletRequest("PATCH", URI(url).path).apply {
        scheme = "https"
        serverName = "example.test"
        serverPort = 443
    }

    fun signProof(
        key: ECKey,
        userVerification: String,
        issuedAt: Date = Date.from(TEST_NOW),
        headerJwk: JWK = key.toPublicJWK(),
        type: String = "device-proof+jwt"
    ): String {
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType(type))
            .jwk(headerJwk)
            .build()
        val claims = JWTClaimsSet.Builder()
            .jwtID(UUID.randomUUID().toString())
            .issueTime(issuedAt)
            .claim("htm", "PATCH")
            .claim("htu", url)
            .claim("userVerification", userVerification)
            .build()
        return SignedJWT(header, claims).apply { sign(ECDSASigner(key.toECPrivateKey())) }.serialize()
    }

    fun failureOf(result: Result<VerifiedDeviceProof>): DpopFailure =
        shouldThrow<DpopValidationException> { result.getOrThrow() }.failure

    given("a validly signed, fresh device proof") {
        val key = ECKeyGenerator(Curve.P_256).generate()
        val proof = signProof(key, "biometric")

        `when`("validating it") {
            val result = validator.validate(proof, request)

            then("it succeeds and reports the userVerification the proof carried") {
                result.userVerification shouldBe UserVerification.BIOMETRIC
                result.publicKey.thumbprint shouldBe JwkThumbprintService().computeThumbprint(key.toPublicJWK())
            }
        }

        `when`("validating that same proof a second time") {
            val result = runCatching { validator.validate(proof, request) }

            then("it is rejected as a replay") {
                failureOf(result) shouldBe DpopFailure.REPLAY
            }
        }
    }

    given("a channel DPoP proof (typ dpop+jwt) presented as a device proof") {
        val proof = signProof(ECKeyGenerator(Curve.P_256).generate(), "biometric", type = "dpop+jwt")

        `when`("validating it") {
            val result = runCatching { validator.validate(proof, request) }

            then("it is rejected for its type: a channel key never stands in for a device credential") {
                failureOf(result) shouldBe DpopFailure.WRONG_TYPE
            }
        }
    }

    given("a device proof signed more than maxProofAgeSeconds ago") {
        val staleProof = signProof(ECKeyGenerator(Curve.P_256).generate(), "pin", issuedAt = Date.from(TEST_NOW.minusSeconds(600)))

        `when`("validating it") {
            val result = runCatching { validator.validate(staleProof, request) }

            then("it is rejected as too old") {
                failureOf(result) shouldBe DpopFailure.IAT_TOO_OLD
            }
        }
    }

    given("a proof whose header carries a different key than the one that signed it") {
        val headerKey = ECKeyGenerator(Curve.P_256).generate()
        val signingKey = ECKeyGenerator(Curve.P_256).generate()
        val tamperedProof = signProof(signingKey, "pin", headerJwk = headerKey.toPublicJWK())

        `when`("validating it") {
            val result = runCatching { validator.validate(tamperedProof, request) }

            then("the signature check fails") {
                failureOf(result) shouldBe DpopFailure.INVALID_SIGNATURE
            }
        }
    }

    given("a device proof with an unsupported userVerification value") {
        val proof = signProof(ECKeyGenerator(Curve.P_256).generate(), "voiceprint")

        `when`("validating it") {
            val result = runCatching { validator.validate(proof, request) }

            then("it is rejected for its claims") {
                failureOf(result) shouldBe DpopFailure.INVALID_CLAIMS
            }
        }
    }

    given("no device proof at all") {
        `when`("validating a null proof") {
            val result = runCatching { validator.validate(null, request) }

            then("it is rejected as missing") {
                failureOf(result) shouldBe DpopFailure.MISSING
            }
        }
    }
})
