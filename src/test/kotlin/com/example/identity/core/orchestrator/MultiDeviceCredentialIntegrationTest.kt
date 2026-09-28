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
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
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
        beforeEach {
            // Lazy on purpose: currentChannelKey is reassigned mid-test for a second device.
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
        val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-device").nextRaw()["toolSessionId"] as String
        val patchUrl = "/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-device"
        val proof = signDeviceProof(deviceKey, "http://localhost:$port$patchUrl", "biometric")
        return patch(patchUrl, """{"deviceProof":"$proof","label":"$label"}""")
    }

    init {
        given("several physical devices, each with their own enroll-device credential, on the same account") {
        then("Two devices can each hold their own active credential without deactivating each other") {
            // Device A registers the account and enrolls its own key.
            val deviceAKey = ECKeyGenerator(Curve.P_256).generate()
            // Its own DPoP channel key beside the device credential - enroll-device refuses the same key.
            currentChannelKey = ECKeyGenerator(Curve.P_256).generate()
            val channelASessionId = identifyAndConfirmEmail()
            enrollDevice(channelASessionId, deviceAKey, "Laptop")

            // Device B, never linked, re-identifies into the same account via the PERSON_ID anchor
            // and enrolls its own key. Device A's credential must stay active.
            val deviceBKey = ECKeyGenerator(Curve.P_256).generate()
            // Its own DPoP channel key beside the device credential - enroll-device refuses the same key.
            currentChannelKey = ECKeyGenerator(Curve.P_256).generate()
            val channelBSessionId = identifyAndConfirmEmail()
            enrollDevice(channelBSessionId, deviceBKey, "Handy")

            @Suppress("UNCHECKED_CAST")
            val methods = get("/orchestrator/api/v1/channels/$channelBSessionId/methods")["methods"] as List<Map<String, Any?>>
            val deviceEntries = methods.filter { it["method"] == "device" }
            deviceEntries shouldHaveSize 2
            deviceEntries.map { it["label"] } shouldContainExactlyInAnyOrder listOf("Laptop", "Handy")
        }
        then("Auth device is only offered and resolvable on the device holding the matching key") {
            val deviceAKey = ECKeyGenerator(Curve.P_256).generate()
            // Its own DPoP channel key beside the device credential - enroll-device refuses the same key.
            currentChannelKey = ECKeyGenerator(Curve.P_256).generate()
            val channelASessionId = identifyAndConfirmEmail()
            enrollDevice(channelASessionId, deviceAKey, "Laptop")

            // Key A again: DeviceAccountLink recognizes it, and the single candidate auth-device is
            // offered directly.
            val secondChannelOnDeviceA = post("/orchestrator/api/v1/app/channels")
            secondChannelOnDeviceA.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-device", "step" to "auth")

            // Device B without its own credential re-identifies. Device A's instance belongs to another
            // bindingKeyRef, so enrollment is offered instead of auth-device for a key B doesn't hold.
            val deviceBKey = ECKeyGenerator(Curve.P_256).generate()
            // Its own DPoP channel key beside the device credential - enroll-device refuses the same key.
            currentChannelKey = ECKeyGenerator(Curve.P_256).generate()
            val channelBSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
            val identToolSessionId = post("/orchestrator/api/v1/channels/$channelBSessionId/tools/ident-fsc").nextRaw()["toolSessionId"] as String
            val reidentified = patch(
                "/orchestrator/api/v1/tools/$identToolSessionId/ident-fsc",
                """{"kvnr":"A123456789","familyName":"Muster","givenNames":"Max","birthDate":"1985-06-15","fsc":"VALIDCODE"}"""
            )
            // Several enrollment candidates lead to a selection page, never to auth-device.
            reidentified.next() shouldBe mapOf("type" to "orchestrator", "context" to "enrollment", "step" to "selectMethod")
            @Suppress("UNCHECKED_CAST")
            val options = reidentified["stepData"].let { (it as Map<String, Any?>)["options"] as List<String> }
            options shouldContain "enroll-device"
            options shouldNotContain "auth-device"
        }
        }
    }
}
