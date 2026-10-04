package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.dpop.DpopProof
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.ECDSASigner
import com.nimbusds.jose.jwk.Curve
import com.nimbusds.jose.jwk.ECKey
import com.nimbusds.jose.jwk.gen.ECKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import io.mockk.every
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Several physical devices, each with their own enroll-device credential, on the same account
 * (docs/03-tool-architektur.md, allowsMultipleInstances). Own file because it needs real ECDSA
 * signing, which a mocked JwkThumbprintService would defeat. The channel key varies via
 * [currentChannelKey], and the real JwkThumbprintService derives a distinct bindingKeyRef per key.
 */
class MultiDeviceCredentialIntegrationTest : IntegrationTestSupport() {

    private var currentChannelKey: ECKey = ECKeyGenerator(Curve.P_256).generate()

    init {
        beforeScenario {
            // Lazy on purpose: currentChannelKey is reassigned mid-scenario for a second device.
            every { dpopValidator.validate(any(), any(), any()) } answers {
                DpopProof(
                    token = "mock-token",
                    publicKey = currentChannelKey.toPublicJWK(),
                    jti = UUID.randomUUID().toString(),
                    htm = "POST",
                    htu = "http://localhost/mock",
                    issuedAt = Instant.now(),
                    nonce = null
                )
            }
        }
    }

    private fun signDeviceProof(deviceKey: ECKey, htu: String, userVerification: String, issuedAt: Date = Date()): String {
        val header = JWSHeader.Builder(JWSAlgorithm.ES256)
            .type(JOSEObjectType("device-proof+jwt"))
            .jwk(deviceKey.toPublicJWK())
            .build()
        val claims = JWTClaimsSet.Builder()
            .jwtID(UUID.randomUUID().toString())
            .issueTime(issuedAt)
            .claim("htm", "PATCH")
            .claim("htu", htu)
            .claim("userVerification", userVerification)
            .build()
        val signedJWT = SignedJWT(header, claims)
        signedJWT.sign(ECDSASigner(deviceKey.toECPrivateKey()))
        return signedJWT.serialize()
    }
    private fun enrollDevice(channelSessionId: String, deviceKey: ECKey, label: String): Map<String, Any?> {
        val enrollToolSessionId = post("/tools/api/enroll-device/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String
        val patchUrl = "/tools/api/enroll-device/v1/$enrollToolSessionId"
        val proof = signDeviceProof(deviceKey, "http://localhost:$port$patchUrl", "biometric")
        return patch(patchUrl, """{"deviceProof":"$proof","label":"$label"}""")
    }

    init {
        given("two physical devices, each with its own DPoP channel key") {
            `when`("device A registers and enrolls its key, then device B, never linked, re-identifies into the same account and enrolls its own") {
                // Each device has its own DPoP channel key beside its credential - enroll-device refuses the same key.
                currentChannelKey = ECKeyGenerator(Curve.P_256).generate()
                val channelASessionId = identifyAndConfirmEmail()
                enrollDevice(channelASessionId, ECKeyGenerator(Curve.P_256).generate(), "Laptop")

                // Re-identification finds the account via the PERSON_ID anchor.
                currentChannelKey = ECKeyGenerator(Curve.P_256).generate()
                val channelBSessionId = identifyAndConfirmEmail()
                enrollDevice(channelBSessionId, ECKeyGenerator(Curve.P_256).generate(), "Handy")

                @Suppress("UNCHECKED_CAST")
                val methods = get("/orchestrator/api/v1/channels/$channelBSessionId/methods")["methods"] as List<Map<String, Any?>>

                then("both devices hold their own active credential without deactivating each other") {
                    val deviceEntries = methods.filter { it["method"] == "device" }
                    deviceEntries shouldHaveSize 2
                    deviceEntries.map { it["label"] } shouldContainExactlyInAnyOrder listOf("Laptop", "Handy")
                }
            }
        }
    }
}
