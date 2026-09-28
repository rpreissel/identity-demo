package com.example.identity.simulation.kobil

import com.example.identity.contract.texts.Text
import com.example.identity.simulation.kobil.internal.SsmsAssertion
import com.example.identity.simulation.kobil.internal.SsmsAssertionRepository
import com.example.identity.simulation.kobil.internal.SsmsUser
import com.example.identity.simulation.kobil.internal.SsmsUserRepository
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.SecureRandom
import java.time.Clock
import java.util.UUID

/** A user as KOBIL knows it: an id within a tenant. */
data class KobilUserRef(val tenantId: String, val userId: String)

/**
 * A risk signal an activated device reports about itself. Named conditions rather than a score,
 * because a made-up number is worse than no measurement. Which ones block a login is decided by
 * `auth_kobil`.
 */
enum class KobilRisk {
    ROOTED,
    EMULATOR,
    DEBUGGER_ATTACHED,
    APP_TAMPERED,
    OS_OUTDATED,
}

/**
 * What KOBIL hands back when a relying party redeems a one-time password: the device that
 * produced the assertion and its reported risks. The assertion never passes through the client.
 */
data class KobilOtpVerification(val deviceId: String, val risks: Set<KobilRisk>)

/** Raised when the app-facing side is called with something KOBIL does not accept. */
class KobilRejectedException(val text: Text) : RuntimeException(text.template)

/**
 * The simulated KOBIL backend. [activate] and [login] are what the app's MC SDK does (over HTTP via
 * `KobilMockController`); everything else is what a relying party's backend calls. Operations are
 * named after SSMS tasks, never after our flow, so the shape carries over to the real service.
 */
@Service
class KobilSsms(
    private val users: SsmsUserRepository,
    private val assertions: SsmsAssertionRepository,
    private val clock: Clock,
) {

    private val random = SecureRandom()

    // ------------------------------------------------------------------ management side

    @Transactional
    fun provisionUser(tenantId: String, subjectRef: String): KobilUserRef {
        val userId = "kob-" + UUID.randomUUID().toString().take(12)
        users.save(SsmsUser(userId = userId, tenantId = tenantId, subjectRef = subjectRef, createdAt = clock.instant()))
        return KobilUserRef(tenantId, userId)
    }

    @Transactional
    fun issueActivationCode(user: KobilUserRef): String {
        val stored = load(user)
        val code = (1..8).map { ACTIVATION_ALPHABET[random.nextInt(ACTIVATION_ALPHABET.length)] }.joinToString("")
        stored.activationCode = code
        return code
    }

    @Transactional
    fun setPin(user: KobilUserRef, pin: String) {
        load(user).pin = pin
    }

    /** The device identifier ("Kennung"), or null while no device has completed activation yet. */
    @Transactional(readOnly = true)
    fun deviceOf(user: KobilUserRef): String? = load(user).deviceId

    /**
     * Redeems a one-time password. Returns null when the OTP is unknown, already spent, or does
     * not belong to [user] - all three are the same answer to a caller, deliberately: telling
     * them apart would say something about other people's sessions.
     */
    @Transactional
    fun verifyOtp(user: KobilUserRef, otp: String): KobilOtpVerification? {
        val assertion = assertions.findByIdOrNull(otp) ?: return null
        if (assertion.userId != user.userId || assertion.redeemedAt != null) return null
        assertion.redeemedAt = clock.instant()
        return KobilOtpVerification(assertion.deviceId!!, parseRisks(assertion.riskSignals))
    }

    @Transactional
    fun deprovisionUser(user: KobilUserRef) {
        assertions.deleteByUserId(user.userId)
        users.deleteById(user.userId)
    }

    /**
     * Demo switch with no counterpart in the real product: declares what the device's sensors
     * will report from now on. Without it the risk path would only ever exist in a test.
     */
    @Transactional
    fun simulateRiskSignals(user: KobilUserRef, risks: Set<KobilRisk>) {
        load(user).riskSignals = risks.joinToString(",") { it.name }
    }

    // ------------------------------------------------------------------ app (MC SDK) side

    /**
     * The MC SDK's `ActivateEvent`: binds this device to the user. The device identifier is
     * created here, by KOBIL - the relying party learns it afterwards, never chooses it.
     */
    @Transactional
    fun activate(user: KobilUserRef, activationCode: String, pin: String): String {
        val stored = load(user)
        if (stored.activationCode == null || stored.activationCode != activationCode) {
            throw KobilRejectedException(Text("Aktivierungscode ungueltig"))
        }
        if (stored.pin != pin) {
            throw KobilRejectedException(Text("PIN ungueltig"))
        }
        // One activation code, one activation - as with any real activation secret.
        stored.activationCode = null
        stored.deviceId = "dev-" + UUID.randomUUID().toString().take(12)
        return stored.deviceId!!
    }

    /**
     * The MC SDK's `LoginEvent`: checks the device and files an assertion. The caller gets only
     * the one-time password that points at it.
     */
    @Transactional
    fun login(user: KobilUserRef, pin: String): String {
        val stored = load(user)
        val deviceId = stored.deviceId ?: throw KobilRejectedException(Text("Geraet nicht aktiviert"))
        if (stored.pin != pin) {
            throw KobilRejectedException(Text("PIN ungueltig"))
        }
        val otp = (1..8).map { random.nextInt(10) }.joinToString("")
        assertions.save(
            SsmsAssertion(
                otp = otp,
                userId = stored.userId,
                deviceId = deviceId,
                riskSignals = stored.riskSignals,
                createdAt = clock.instant(),
            )
        )
        return otp
    }

    // ------------------------------------------------------------------ internals

    private fun load(user: KobilUserRef): SsmsUser {
        val stored = users.findByIdOrNull(user.userId) ?: throw KobilRejectedException(Text("Unbekannter Nutzer"))
        if (stored.tenantId != user.tenantId) throw KobilRejectedException(Text("Unbekannter Nutzer"))
        return stored
    }

    private fun parseRisks(raw: String?): Set<KobilRisk> =
        raw?.split(",")
            ?.mapNotNull { name -> KobilRisk.entries.find { it.name == name.trim() } }
            ?.toSet()
            ?: emptySet()

    private companion object {
        /** Crockford-ish: no I/O/U/1/0, so an activation code stays readable when typed over. */
        const val ACTIVATION_ALPHABET = "ABCDEFGHJKLMNPQRSTVWXYZ23456789"
    }
}
