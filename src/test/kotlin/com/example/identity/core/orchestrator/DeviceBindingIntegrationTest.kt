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
import io.mockk.every
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.maps.shouldNotContainKeys
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException
import java.io.ByteArrayOutputStream
import java.io.PrintStream
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
        beforeEach {
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
        val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-device").nextRaw()["toolSessionId"] as String
        val patchUrl = "/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-device"
        val proof = signDeviceProof(deviceKey, "http://localhost:$port$patchUrl", userVerification)
        patch(patchUrl, """{"deviceProof":"$proof"}""")
        return deviceKey
    }

    init {
        given("enroll-device and auth-device with real ECDSA signing") {
        then("Enroll device reaches loa 2 directly with geraet and access means in amr") {
            val channelSessionId = identifyAndConfirmEmail()
            val deviceKey = enrollDevice(channelSessionId, userVerification = "biometric")
            deviceKey.shouldNotBeNull()

            val channel = get("/orchestrator/api/v1/channels/$channelSessionId")
            channel.channel()["currentAcr"] shouldBe "loa2"
            // "fsc" is also present (accumulated across the session); only device/biometric are asserted.
            @Suppress("UNCHECKED_CAST")
            (channel.channel()["currentAmr"] as List<String>).shouldContainAll("device", "biometric")
            @Suppress("UNCHECKED_CAST")
            (channel.channel()["activeMethods"] as List<*>).methodNames() shouldContain "device"
        }
        then("Auth device with the enrolled key recognizes the device and reaches loa 2 on a new channel") {
            val channelSessionId = identifyAndConfirmEmail()
            val deviceKey = enrollDevice(channelSessionId)

            // Same DPoP binding key (channelKey) -> a brand-new channel recognizes the device via
            // DeviceAccountLink and offers auth-device straight away, single-candidate skip.
            val newChannel = post("/orchestrator/api/v1/app/channels")
            newChannel.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-device", "step" to "auth")
            val newChannelSessionId = newChannel.channel()["channelSessionId"] as String

            // Recognizing the device resolves an accountId, but nothing is proven on this channel yet,
            // so the account's active methods must not leak before the auth-device proof
            // (ChannelSession.hasProvenFactor).
            newChannel.channel().shouldNotContainKeys("currentAcr", "currentAmr", "activeMethods")

            val authToolSessionId = post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-device").nextRaw()["toolSessionId"] as String
            val authPatchUrl = "/orchestrator/api/v1/tools/$authToolSessionId/auth-device"
            val proof = signDeviceProof(deviceKey, "http://localhost:$port$authPatchUrl", "pin")
            val authenticated = patch(authPatchUrl, """{"deviceProof":"$proof"}""")
            authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")

            val afterLogin = get("/orchestrator/api/v1/channels/$newChannelSessionId")
            afterLogin.channel()["currentAcr"] shouldBe "loa2"
            @Suppress("UNCHECKED_CAST")
            afterLogin.channel()["currentAmr"] as List<String> shouldContainExactlyInAnyOrder listOf("device", "pin")
        }
        then("Auth device declined on a fresh channel falls through to the remaining auth methods") {
            // Registration enrols sms (and the email obligation adds email), then a device credential
            // on top - so declining the device leaves genuine alternatives to choose from.
            val channelSessionId = identifyAndConfirmEmail()
            enrollSms(channelSessionId)
            enrollPassword(channelSessionId)
            // sms + password already finish the journey, so the device credential is added
            // afterwards through MANAGE - the loa2 gate is satisfied by this session's own ident-fsc.
            post("/orchestrator/api/v1/channels/$channelSessionId/enrollments")
            enrollDevice(channelSessionId)

            // Fresh session on the same device: the first state of the FAST fallback chain, the device method.
            val newChannel = post("/orchestrator/api/v1/app/channels")
            val newChannelSessionId = newChannel.channel()["channelSessionId"] as String
            newChannel.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-device", "step" to "auth")

            // Declining is not cancelling: the chain falls through to the account's other methods.
            val toolSessionId = post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-device").nextRaw()["toolSessionId"] as String
            val afterDecline = delete("/orchestrator/api/v1/tools/$toolSessionId/auth-device")
            afterDecline.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
            @Suppress("UNCHECKED_CAST")
            afterDecline.stepData()["options"] as List<String> shouldContainExactlyInAnyOrder listOf("auth-sms", "auth-password")

            // Cancelling the whole journey restarts the same intent and so lands back on the first state.
            val afterCancel = delete("/orchestrator/api/v1/channels/$newChannelSessionId/journey")
            afterCancel.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-device", "step" to "auth")
        }
        then("Auth device signed with a different key is rejected as failed not as someone elses credential") {
            val channelSessionId = identifyAndConfirmEmail()
            enrollDevice(channelSessionId)

            val newChannel = post("/orchestrator/api/v1/app/channels")
            val newChannelSessionId = newChannel.channel()["channelSessionId"] as String
            val authToolSessionId = post("/orchestrator/api/v1/channels/$newChannelSessionId/tools/auth-device").nextRaw()["toolSessionId"] as String
            val authPatchUrl = "/orchestrator/api/v1/tools/$authToolSessionId/auth-device"

            val wrongKey = ECKeyGenerator(Curve.P_256).generate()
            val proof = signDeviceProof(wrongKey, "http://localhost:$port$authPatchUrl", "pin")
            val result = patch(authPatchUrl, """{"deviceProof":"$proof"}""")

            // Failed, not Completed: retried in place, with no error revealing which device was expected.
            templateOf(result.stepData()["error"]) shouldBe "Geraet nicht erkannt"
            result.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-device", "step" to "auth")
        }
        then("Enroll device with an expired proof is rejected as unauthorized") {
            val channelSessionId = identifyAndConfirmEmail()
            val deviceKey = ECKeyGenerator(Curve.P_256).generate()
            val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-device").nextRaw()["toolSessionId"] as String
            val patchUrl = "/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-device"
            val staleProof = signDeviceProof(
                deviceKey, "http://localhost:$port$patchUrl", "pin",
                issuedAt = Date.from(Instant.now().minusSeconds(600))
            )

            val exception = org.junit.jupiter.api.assertThrows<HttpClientErrorException> {
                restTemplate.exchange(
                    "http://localhost:$port$patchUrl", HttpMethod.PATCH,
                    HttpEntity("""{"deviceProof":"$staleProof"}""", headers()), mapType
                )
            }
            exception.statusCode shouldBe HttpStatus.UNAUTHORIZED
        }
        then("Enroll device replaying the same proof re-runs nothing - the tool session is already consumed") {
            // A completed enroll-device tool session expires on completion, so the replay is refused
            // before the handler. DeviceProofValidator's jti+thumbprint replay protection stays
            // underneath as defense in depth.
            val channelSessionId = identifyAndConfirmEmail()
            val deviceKey = ECKeyGenerator(Curve.P_256).generate()
            val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-device").nextRaw()["toolSessionId"] as String
            val patchUrl = "/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-device"
            val proof = signDeviceProof(deviceKey, "http://localhost:$port$patchUrl", "pin")

            patch(patchUrl, """{"deviceProof":"$proof"}""")

            val replay = org.junit.jupiter.api.assertThrows<HttpClientErrorException> {
                restTemplate.exchange(
                    "http://localhost:$port$patchUrl", HttpMethod.PATCH,
                    HttpEntity("""{"deviceProof":"$proof"}""", headers()), mapType
                )
            }
            replay.statusCode shouldBe HttpStatus.NOT_FOUND

            // Exactly one device credential exists; the replay created none.
            @Suppress("UNCHECKED_CAST")
            val methods = get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>
            methods.count { it["method"] == "device" } shouldBe 1
        }
        then("Manage methods enroll device requires loa 2 first even though the channel is already authenticated") {
            // Register and enroll via sms only (loa1), deliberately not via enroll-device, so the
            // session's currentAcr stays loa1 after login. The registration channel offers no other
            // method, so it completes with sms alone and the device is linked (ADR-46).
            val channelSessionId = identifyAndConfirmEmail(availableTools = listOf("ident-fsc", "confirm-email", "enroll-sms", "auth-sms"))
            val enrollToolSessionId = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-sms").nextRaw()["toolSessionId"] as String
            val (tan, _) = captureMockTan {
                patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"phoneNumber":"+49 170 1234567"}""")
            }
            patch("/orchestrator/api/v1/tools/$enrollToolSessionId/enroll-sms", """{"tan":"$tan"}""")

            val newChannel = post("/orchestrator/api/v1/app/channels")
            val newChannelSessionId = newChannel.channel()["channelSessionId"] as String
            authenticateViaSms(newChannelSessionId)

            val afterLogin = get("/orchestrator/api/v1/channels/$newChannelSessionId")
            afterLogin.channel()["currentAcr"] shouldBe "loa1"

            // MANAGE with enroll-device as goal forces the loa2 step-up first (selfServiceAcrFloor; the
            // account is identified). The session's loa1 evidence cannot add a loa2-capable credential,
            // and sms is already used, so re-identification is offered via the RE_IDENTIFY sub-journey
            // (ReIdentifyState.OfferReIdent).
            val started = triggerEnrollmentStepUp(newChannelSessionId)
            started.next() shouldBe mapOf("type" to "orchestrator", "context" to "prompt", "step" to "confirm")
            // Candidate computation is unit-tested (ReIdentifyStrategyTest); here only the HTTP round
            // trip through the sub-journey matters.
            val accepted = post("/orchestrator/api/v1/channels/$newChannelSessionId/answer", """{"answer":"accept"}""")
            accepted.next() shouldBe mapOf("type" to "orchestrator", "context" to "auth", "step" to "selectMethod")
        }
        }
    }
}
