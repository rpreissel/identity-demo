package com.example.identity.core.orchestrator

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainAll
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe

/**
 * enroll-kobil / auth-kobil end to end over HTTP. The test plays the MC SDK and calls the
 * `kobil` endpoints directly, as a phone would. That makes the central property observable:
 * the one-time password travels through the client, the assertion behind it never does. Nothing
 * about KOBIL is stubbed. Invisibility from another installation rests on `keyBinding` and is
 * covered by `KobilDescriptorsTest`.
 */
class KobilBindingIntegrationTest : IntegrationTestSupport() {

    init {
        beforeScenario { stubDpopWithFakeJwk() }
    }

    /** What the app ends up holding after a setup: the SDK's addressing data plus its local secret. */
    private data class EnrolledDevice(val tenantId: String, val kobilUserId: String, val unlockSecret: String)

    private fun enrollKobil(channelSessionId: String): EnrolledDevice {
        val activated = post("/orchestrator/api/v1/channels/$channelSessionId/tools/enroll-kobil")
        val toolSessionId = activated.nextRaw()["toolSessionId"] as String
        val stepData = activated.stepData()

        val device = EnrolledDevice(
            tenantId = stepData["tenantId"] as String,
            kobilUserId = stepData["kobilUserId"] as String,
            unlockSecret = stepData["unlockSecret"] as String,
        )

        // The SDK's activation - straight to the provider, not through our backend.
        post(
            "/mock-kobil/activate",
            """{"tenantId":"${device.tenantId}","userId":"${device.kobilUserId}","activationCode":"${stepData["activationCode"]}","pin":"${stepData["pin"]}"}"""
        )

        patch(
            "/orchestrator/api/v1/tools/$toolSessionId/enroll-kobil",
            """{"activated":true,"biometricConsent":true,"label":"Testhandy"}"""
        )
        return device
    }

    private fun startAuth(channelSessionId: String): String {
        val started = post("/orchestrator/api/v1/channels/$channelSessionId/tools/auth-kobil")
        started.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-kobil", "step" to "unlock")
        return started.nextRaw()["toolSessionId"] as String
    }

    private fun releasePin(toolSessionId: String, unlock: String): Map<String, Any?> =
        post("/orchestrator/api/v1/tools/$toolSessionId/auth-kobil/pin-releases", """{"unlock":$unlock}""")

    private fun biometric(device: EnrolledDevice) = """{"kind":"biometric","unlockSecret":"${device.unlockSecret}"}"""

    /** The SDK's login: the app gets a one-time password, nothing else. */
    private fun sdkLogin(device: EnrolledDevice, pin: String): String = post(
        "/mock-kobil/login",
        """{"tenantId":"${device.tenantId}","userId":"${device.kobilUserId}","pin":"$pin"}"""
    )["otp"] as String

    private fun redeem(toolSessionId: String, otp: String): Map<String, Any?> =
        patch("/orchestrator/api/v1/tools/$toolSessionId/auth-kobil", """{"otp":"$otp"}""")

    /** The methods whose key-bound credential lives on this device, as device-link lists them. */
    @Suppress("UNCHECKED_CAST")
    private fun boundMethods(): List<String> =
        (get("/orchestrator/api/v1/app/channels/device-link")["boundCredentials"] as List<Map<String, Any?>>)
            .map { it["method"] as String }

    @Suppress("UNCHECKED_CAST")
    private fun activeMethodId(channelSessionId: String, method: String): String =
        (get("/orchestrator/api/v1/channels/$channelSessionId/methods")["methods"] as List<Map<String, Any?>>)
            .first { it["method"] == method }["id"] as String

    private fun passwordEnrolledChannel(): String {
        val channelSessionId = identifyAndConfirmEmail(requiredAcr = "loa2")
        enrollPassword(channelSessionId)
        return channelSessionId
    }

    /** Enrolls on one channel and returns the device plus a fresh, unauthenticated channel to log in on. */
    private fun enrolledDeviceOnFreshChannel(): Pair<EnrolledDevice, String> {
        val enrollChannel = passwordEnrolledChannel()
        val device = enrollKobil(enrollChannel)
        val loginChannel = post("/orchestrator/api/v1/app/channels").channel()["channelSessionId"] as String
        return device to loginChannel
    }

    init {
        given("an identified channel with a confirmed address and a password") {
            `when`("the device activates and the app confirms the KOBIL enrollment") {
                val channelSessionId = passwordEnrolledChannel()
                enrollKobil(channelSessionId)
                val channel = get("/orchestrator/api/v1/channels/$channelSessionId").channel()

                then("kobil becomes an active method") {
                    (channel["activeMethods"] as List<*>).methodNames() shouldContain "kobil"
                }
                then("the channel reaches loa2 in one run, from kobil and biometric") {
                    channel["currentAcr"] shouldBe "loa2"
                    (channel["currentAmr"] as List<*>).shouldContainAll("kobil", "biometric")
                }
            }
        }

        given("an enrolled KOBIL credential and a fresh channel") {
            `when`("the biometric unlock releases the PIN and the SDK's one-time password is redeemed") {
                val (device, loginChannel) = enrolledDeviceOnFreshChannel()
                val toolSessionId = startAuth(loginChannel)
                val released = releasePin(toolSessionId, biometric(device))
                val authenticated = redeem(toolSessionId, sdkLogin(device, released.stepData()["kobilPin"] as String))
                val channel = get("/orchestrator/api/v1/channels/$loginChannel").channel()

                then("the release moves on to the OTP step") {
                    released.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-kobil", "step" to "otp")
                }
                then("the redeemed OTP authenticates") {
                    authenticated.next() shouldBe mapOf("type" to "orchestrator", "context" to "authentication", "step" to "authenticated")
                }
                then("the channel reaches loa2 from kobil and biometric") {
                    channel["currentAcr"] shouldBe "loa2"
                    (channel["currentAmr"] as List<*>).shouldContainAll("kobil", "biometric")
                }
            }

            `when`("the password unlock releases the PIN and the one-time password is redeemed") {
                val (device, loginChannel) = enrolledDeviceOnFreshChannel()
                val toolSessionId = startAuth(loginChannel)
                val pin = releasePin(toolSessionId, """{"kind":"password","password":"correct-horse-battery"}""")
                    .stepData()["kobilPin"] as String
                redeem(toolSessionId, sdkLogin(device, pin))
                val amr = get("/orchestrator/api/v1/channels/$loginChannel").channel()["currentAmr"] as List<*>

                then("the run reports pin, never password - otherwise the account password would count twice") {
                    amr.shouldContainAll("kobil", "pin")
                    amr shouldNotContain "password"
                }
            }

            `when`("the PIN is released and the tool session is read again") {
                val (device, loginChannel) = enrolledDeviceOnFreshChannel()
                val toolSessionId = startAuth(loginChannel)
                val released = releasePin(toolSessionId, biometric(device))
                val reread = get("/orchestrator/api/v1/tools/$toolSessionId/auth-kobil")

                then("the release response carries the PIN") {
                    released.stepData().keys shouldContain "kobilPin"
                }
                then("the later read stays at the OTP step and does not repeat it") {
                    reread.next() shouldBe mapOf("type" to "tool", "toolId" to "auth-kobil", "step" to "otp")
                    reread.stepData().keys shouldNotContain "kobilPin"
                }
            }

            `when`("a session that proved loa2 through kobil removes the credential") {
                val (device, loginChannel) = enrolledDeviceOnFreshChannel()
                // The identifier KOBIL assigned, which the client cannot learn any other way: it
                // never travels through an assertion the client gets to see.
                val boundBefore = boundMethods()
                val toolSessionId = startAuth(loginChannel)
                val pin = releasePin(toolSessionId, biometric(device)).stepData()["kobilPin"] as String
                redeem(toolSessionId, sdkLogin(device, pin))
                delete("/orchestrator/api/v1/channels/$loginChannel/methods/${activeMethodId(loginChannel, "kobil")}")
                val boundAfter = boundMethods()

                then("the provider's binding was readable from the device link") {
                    boundBefore shouldContain "kobil"
                }
                then("it vanishes with the credential - the client's signal to drop its unlock secret") {
                    boundAfter shouldNotContain "kobil"
                }
            }
        }
    }
}
