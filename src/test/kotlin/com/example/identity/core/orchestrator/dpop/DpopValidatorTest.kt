package com.example.identity.core.orchestrator.dpop

import com.example.identity.TEST_CLOCK
import com.example.identity.TEST_NOW
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.JWSSigner
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.JWK
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import java.util.Date
import java.util.UUID

/**
 * Unit test of [DpopValidator]'s RFC 9449 proof checks, without Spring or HTTP. Integration tests mock
 * `DpopValidator`, so this is the only place its logic runs. [JwkThumbprintService] and
 * [DpopReplayProtectionService] are real, as in [DeviceProofValidatorTest]. How `htu` is compared
 * is pinned by [HtuMatchesTest].
 */
class DpopValidatorTest : BehaviorSpec({

    fun validator() = DpopValidator(
        jwkThumbprintService = JwkThumbprintService(),
        replayProtectionService = DpopReplayProtectionService(inMemoryReplayRepository(), clock = TEST_CLOCK),
        maxClockSkewSeconds = 30,
        maxProofAgeSeconds = 60,
        clock = TEST_CLOCK
    )

    val method = "POST"
    val url = "https://example.test/orchestrator/api/v1/channels"

    /** A proof for [method] and [url]; each parameter breaks one part of it. `null` leaves the part out. */
    fun signProof(
        key: ECKey,
        htm: String = method,
        htu: String = url,
        issuedAt: Date = Date.from(TEST_NOW),
        jti: String? = UUID.randomUUID().toString(),
        nonce: String? = null,
        headerJwk: JWK? = key.toPublicJWK(),
        type: String? = "dpop+jwt",
        algorithm: JWSAlgorithm = JWSAlgorithm.ES256,
        signer: JWSSigner = ECDSASigner(key.toECPrivateKey())
    ): String {
        val header = JWSHeader.Builder(algorithm)
            .apply { type?.let { type(JOSEObjectType(it)) } }
            .jwk(headerJwk)
            .build()
        val claims = JWTClaimsSet.Builder()
            .jwtID(jti)
            .issueTime(issuedAt)
            .claim("htm", htm)
            .claim("htu", htu)
            .apply { nonce?.let { claim("nonce", it) } }
            .build()
        return SignedJWT(header, claims).apply { sign(signer) }.serialize()
    }

    fun failureOf(result: Result<DpopProof>): DpopFailure =
        shouldThrow<DpopValidationException> { result.getOrThrow() }.failure

    given("a validly signed, fresh DPoP proof with a nonce") {
        val key = ECKeyGenerator(Curve.P_256).generate()
        val jti = UUID.randomUUID().toString()
        val proof = signProof(key, jti = jti, nonce = "server-nonce")

        `when`("validating it") {
            val result = validator().validate(proof, method, url)

            then("it reports the proof's own claims and key") {
                result.jti shouldBe jti
                result.htm shouldBe method
                result.htu shouldBe url
                result.nonce shouldBe "server-nonce"
                JwkThumbprintService().computeThumbprint(result.publicKey) shouldBe
                    JwkThumbprintService().computeThumbprint(key.toPublicJWK())
            }
        }
    }

    given("a validly signed, fresh DPoP proof without a nonce") {
        val proof = signProof(ECKeyGenerator(Curve.P_256).generate())

        `when`("validating it") {
            val result = validator().validate(proof, method, url)

            then("the result carries no nonce either, rather than inventing one") {
                result.nonce.shouldBeNull()
            }
        }
    }

    given("a proof the validator has already accepted once") {
        val proof = signProof(ECKeyGenerator(Curve.P_256).generate())
        val validator = validator()
        validator.validate(proof, method, url)

        `when`("validating the same proof a second time") {
            val result = runCatching { validator.validate(proof, method, url) }

            then("it is rejected as a replay") {
                failureOf(result) shouldBe DpopFailure.REPLAY
            }
        }
    }

    given("no proof at all") {
        `when`("validating a null proof") {
            val result = runCatching { validator().validate(null, method, url) }

            then("it is rejected as missing") {
                failureOf(result) shouldBe DpopFailure.MISSING
            }
        }

        `when`("validating a blank proof") {
            val result = runCatching { validator().validate("   ", method, url) }

            then("it is rejected as missing") {
                failureOf(result) shouldBe DpopFailure.MISSING
            }
        }
    }

    given("a proof that isn't a well-formed JWT at all") {
        `when`("validating it") {
            val result = runCatching { validator().validate("not-a-jwt", method, url) }

            then("it is rejected as malformed") {
                failureOf(result) shouldBe DpopFailure.MALFORMED
            }
        }
    }

    given("a proof with header problems") {
        val key = ECKeyGenerator(Curve.P_256).generate()
        val rsaKey = RSAKeyGenerator(2048).generate()

        `when`("the typ header is missing") {
            val result = runCatching { validator().validate(signProof(key, type = null), method, url) }

            then("it is rejected for its type") {
                failureOf(result) shouldBe DpopFailure.WRONG_TYPE
            }
        }

        `when`("the typ header is a plain JWT") {
            val result = runCatching { validator().validate(signProof(key, type = "JWT"), method, url) }

            then("it is rejected for its type") {
                failureOf(result) shouldBe DpopFailure.WRONG_TYPE
            }
        }

        `when`("the proof is signed with RS256") {
            val proof = signProof(
                key,
                headerJwk = rsaKey.toPublicJWK(),
                algorithm = JWSAlgorithm.RS256,
                signer = RSASSASigner(rsaKey.toPrivateKey())
            )
            val result = runCatching { validator().validate(proof, method, url) }

            then("it is rejected for its algorithm") {
                failureOf(result) shouldBe DpopFailure.UNSUPPORTED_ALGORITHM
            }
        }

        `when`("the header carries no JWK") {
            val result = runCatching { validator().validate(signProof(key, headerJwk = null), method, url) }

            then("it is rejected for its key") {
                failureOf(result) shouldBe DpopFailure.INVALID_KEY
            }
        }

        // `jwk.isPrivate` has no reachable test: Nimbus's JWSHeader.Builder.jwk() already rejects a
        // private JWK.

        `when`("the header claims an EC algorithm but carries an RSA JWK") {
            val result = runCatching { validator().validate(signProof(key, headerJwk = rsaKey.toPublicJWK()), method, url) }

            then("it is rejected for its key") {
                failureOf(result) shouldBe DpopFailure.INVALID_KEY
            }
        }

        `when`("the header carries a different key than the one that signed it") {
            val headerKey = ECKeyGenerator(Curve.P_256).generate()
            val result = runCatching { validator().validate(signProof(key, headerJwk = headerKey.toPublicJWK()), method, url) }

            then("the signature check fails") {
                failureOf(result) shouldBe DpopFailure.INVALID_SIGNATURE
            }
        }
    }

    given("a proof whose claims don't match the request") {
        val key = ECKeyGenerator(Curve.P_256).generate()

        `when`("it names a different HTTP method") {
            val result = runCatching { validator().validate(signProof(key, htm = "GET"), method, url) }

            then("it is rejected for its method") {
                failureOf(result) shouldBe DpopFailure.HTM_MISMATCH
            }
        }

        `when`("it names a different URL") {
            val proof = signProof(key, htu = "https://example.test/somewhere-else")
            val result = runCatching { validator().validate(proof, method, url) }

            then("it is rejected for its URL") {
                failureOf(result) shouldBe DpopFailure.HTU_MISMATCH
            }
        }

        `when`("it has no jti") {
            val result = runCatching { validator().validate(signProof(key, jti = null), method, url) }

            then("it is rejected for the missing jti") {
                failureOf(result) shouldBe DpopFailure.JTI_MISSING
            }
        }
    }

    given("a proof whose iat lies outside the window") {
        val key = ECKeyGenerator(Curve.P_256).generate()

        `when`("it was issued beyond the clock-skew allowance in the future") {
            val proof = signProof(key, issuedAt = Date.from(TEST_NOW.plusSeconds(600)))
            val result = runCatching { validator().validate(proof, method, url) }

            then("it is rejected as issued in the future") {
                failureOf(result) shouldBe DpopFailure.IAT_IN_FUTURE
            }
        }

        `when`("it is 90 seconds old, beyond the 60-second window") {
            val proof = signProof(key, issuedAt = Date.from(TEST_NOW.minusSeconds(90)))
            val result = runCatching { validator().validate(proof, method, url) }

            then("it is rejected as too old") {
                failureOf(result) shouldBe DpopFailure.IAT_TOO_OLD
            }
        }
    }

    given("a proof whose iat lies just inside the window") {
        val key = ECKeyGenerator(Curve.P_256).generate()

        `when`("it is 50 seconds old") {
            val issuedAt = TEST_NOW.minusSeconds(50)
            val result = validator().validate(signProof(key, issuedAt = Date.from(issuedAt)), method, url)

            then("it is accepted with its own iat") {
                result.issuedAt shouldBe issuedAt
            }
        }

        `when`("it was issued 29 seconds in the future, inside the clock-skew allowance") {
            val issuedAt = TEST_NOW.plusSeconds(29)
            val result = validator().validate(signProof(key, issuedAt = Date.from(issuedAt)), method, url)

            then("it is accepted with its own iat") {
                result.issuedAt shouldBe issuedAt
            }
        }
    }
})
