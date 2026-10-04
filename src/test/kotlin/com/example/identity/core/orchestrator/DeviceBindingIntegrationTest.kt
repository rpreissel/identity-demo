package com.example.identity.core.orchestrator

import com.example.identity.contract.texts.templateOf
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
import io.kotest.assertions.throwables.shouldThrow
import io.mockk.every
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldNotContainKeys
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.time.Instant
import java.util.Date
import java.util.UUID

/**
 * Device enrollment and authentication with real ECDSA signing. Only DpopValidator (the per-request
 * channel proof) is mocked; JwkThumbprintService and DeviceProofValidator run for real, since a mocked
 * thumbprint service would return the same default value for every key.
 */
class DeviceBindingIntegrationTest : IntegrationTestSupport() {

    // A real EC key, so the unmocked JwkThumbprintService computes a consistent bindingKeyRef.
    private val channelKey = ECKeyGenerator(Curve.P_256).generate()

    init {
        beforeScenario {
            every { dpopValidator.validate(any(), any(), any()) } returns DpopProof(
                token = "mock-token",
                publicKey = channelKey.toPublicJWK(),
                jti = UUID.randomUUID().toString(),
                htm = "POST",
                htu = "http://localhost/mock",
                issuedAt = Instant.now(),
                nonce = null
            )
        }
    }

    /** Self-signed device-proof JWT (typ=device-proof+jwt), same shape DeviceProofValidator expects. */
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

    private fun enrollDevice(channelSessionId: String, userVerification: String = "biometric"): ECKey {
        val deviceKey = ECKeyGenerator(Curve.P_256).generate()
        val enrollToolSessionId = post("/tools/api/enroll-device/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String
        val patchUrl = "/tools/api/enroll-device/v1/$enrollToolSessionId"
        val proof = signDeviceProof(deviceKey, "http://localhost:$port$patchUrl", userVerification)
        patch(patchUrl, """{"deviceProof":"$proof"}""")
        return deviceKey
    }

    /** Sends a PATCH and keeps the outcome, so an HTTP error can be checked in a `then`. */
    private fun rawPatch(url: String, body: String) = runCatching {
        restTemplate.exchange("http://localhost:$port$url", HttpMethod.PATCH, HttpEntity(body, headers()), mapType)
    }

    init {
        given("an identified channel with a confirmed address") {
            `when`("a device is enrolled with biometric user verification") {
                val channelSessionId = identifyAndConfirmEmail()
                enrollDevice(channelSessionId, userVerification = "biometric")
                val channel = get("/orchestrator/api/v1/channels/$channelSessionId").channel()

                then("the channel reaches loa2 directly, with the device and its access means in amr") {
                    channel["currentAcr"] shouldBe "loa2"
                    // "fsc" is also present (accumulated across the session); only device/biometric are asserted.
                    @Suppress("UNCHECKED_CAST")
                    (channel["currentAmr"] as List<String>).shouldContainAll("device", "biometric")
                    (channel["activeMethods"] as List<*>).methodNames() shouldContain "device"
                }
            }

            `when`("enroll-device gets a proof signed long ago") {
                val channelSessionId = identifyAndConfirmEmail()
                val deviceKey = ECKeyGenerator(Curve.P_256).generate()
                val enrollToolSessionId = post("/tools/api/enroll-device/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String
                val patchUrl = "/tools/api/enroll-device/v1/$enrollToolSessionId"
                val staleProof = signDeviceProof(
                    deviceKey, "http://localhost:$port$patchUrl", "pin",
                    issuedAt = Date.from(Instant.now().minusSeconds(600))
                )

                val result = rawPatch(patchUrl, """{"deviceProof":"$staleProof"}""")

                then("an invalid device proof is rejected as unauthorized") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.UNAUTHORIZED
                }
            }

            `when`("the same enroll-device proof is sent twice") {
                // A completed enroll-device tool session expires on completion, so the replay is refused
                // before the handler. DeviceProofValidator's jti+thumbprint replay protection stays
                // underneath as defense in depth.
                val channelSessionId = identifyAndConfirmEmail()
                val deviceKey = ECKeyGenerator(Curve.P_256).generate()
                val enrollToolSessionId = post("/tools/api/enroll-device/v1?channel=$channelSessionId").nextRaw()["toolSessionId"] as String
                val patchUrl = "/tools/api/enroll-device/v1/$enrollToolSessionId"
                val proof = signDeviceProof(deviceKey, "http://localhost:$port$patchUrl", "pin")
                patch(patchUrl, """{"deviceProof":"$proof"}""")

                val replay = rawPatch(patchUrl, """{"deviceProof":"$proof"}""")
                @Suppress("UNCHECKED_CAST")
                val methods = get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>

                then("the replay finds no tool session") {
                    shouldThrow<HttpClientErrorException> { replay.getOrThrow() }.statusCode shouldBe HttpStatus.NOT_FOUND
                }
                then("exactly one device credential exists") {
                    methods.count { it["method"] == "device" } shouldBe 1
                }
            }
        }

        given("a device credential enrolled on this installation") {
            `when`("a fresh channel authenticates with the enrolled key") {
                val deviceKey = enrollDevice(identifyAndConfirmEmail())
                // Same DPoP binding key (channelKey): the new channel recognizes the device via
                // DeviceAccountLink.
                val newChannel = post("/orchestrator/api/v1/app/channels")
                val newChannelSessionId = newChannel.channel()["channelSessionId"] as String
                val authToolSessionId = post("/tools/api/auth-device/v1?channel=$newChannelSessionId").nextRaw()["toolSessionId"] as String
                val authPatchUrl = "/tools/api/auth-device/v1/$authToolSessionId"
                val authenticated = patch(authPatchUrl, """{"deviceProof":"${signDeviceProof(deviceKey, "http://localhost:$port$authPatchUrl", "pin")}"}""")
                val afterLogin = get("/orchestrator/api/v1/channels/$newChannelSessionId").channel()

                then("the new channel offers auth-device straight away") {
                    newChannel.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-device", "step" to "auth")
                }
                then("the account's methods do not leak before the proof") {
                    // Recognizing the device resolves an accountId, but nothing is proven on this
                    // channel yet (ChannelSession.hasProvenFactor).
                    newChannel.channel().shouldNotContainKeys("currentAcr", "currentAmr", "activeMethods")
                }
                then("the proof authenticates and reaches loa2 from device and pin") {
                    authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                    afterLogin["currentAcr"] shouldBe "loa2"
                    @Suppress("UNCHECKED_CAST")
                    (afterLogin["currentAmr"] as List<String>) shouldContainExactlyInAnyOrder listOf("device", "pin")
                }
            }

            `when`("a fresh channel presents a proof signed with a different key") {
                enrollDevice(identifyAndConfirmEmail())
                val newChannelSessionId = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
                val authToolSessionId = post("/tools/api/auth-device/v1?channel=$newChannelSessionId").nextRaw()["toolSessionId"] as String
                val authPatchUrl = "/tools/api/auth-device/v1/$authToolSessionId"
                val wrongKey = ECKeyGenerator(Curve.P_256).generate()

                val result = patch(authPatchUrl, """{"deviceProof":"${signDeviceProof(wrongKey, "http://localhost:$port$authPatchUrl", "pin")}"}""")

                then("it is a failed attempt, retried in place, not someone else's credential") {
                    // No error revealing which device was expected.
                    templateOf(result.stepData()["error"]) shouldBe "Geraet nicht erkannt"
                    result.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-device", "step" to "auth")
                }
            }
        }

        given("an account with sms, password and a device credential on this installation") {
            `when`("a fresh channel declines auth-device and then cancels the journey") {
                // sms + password already finish the registration, so the device credential is added
                // afterwards through MANAGE - the loa2 gate is satisfied by this session's own ident-fsc.
                val channelSessionId = identifyAndConfirmEmail()
                enrollSms(channelSessionId)
                enrollPassword(channelSessionId)
                post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
                enrollDevice(channelSessionId)

                val newChannel = post("/orchestrator/api/v1/app/channels")
                val newChannelSessionId = newChannel.channel()["channelSessionId"] as String
                val toolSessionId = post("/tools/api/auth-device/v1?channel=$newChannelSessionId").nextRaw()["toolSessionId"] as String
                val afterDecline = delete("/tools/api/auth-device/v1/$toolSessionId")
                val afterCancel = delete("/orchestrator/api/v1/channels/$newChannelSessionId/journey")

                then("the fresh channel starts on the first state of the FAST fallback chain, the device method") {
                    newChannel.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-device", "step" to "auth")
                }
                then("declining is not cancelling: the chain falls through to the account's other methods") {
                    afterDecline.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
                    @Suppress("UNCHECKED_CAST")
                    (afterDecline.stepData()["options"] as List<String>) shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")
                }
                then("cancelling restarts the same intent and lands back on the first state") {
                    afterCancel.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-device", "step" to "auth")
                }
            }
        }
    }
}
