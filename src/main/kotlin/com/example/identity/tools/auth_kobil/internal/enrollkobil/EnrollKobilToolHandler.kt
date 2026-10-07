package com.example.identity.tools.auth_kobil.internal.enrollkobil

import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.MasterKeyId
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.auth_kobil.KobilModule
import com.example.identity.tools.auth_kobil.internal.KOBIL_ENROLLMENT_TYPE
import com.example.identity.tools.auth_kobil.internal.KobilEnrollment
import com.example.identity.tools.auth_kobil.internal.KobilPins
import com.example.identity.tools.auth_kobil.internal.KobilEnrollmentRepository
import com.example.identity.tools.auth_kobil.internal.KobilSecrets
import com.example.identity.tools.auth_kobil.internal.kobilFactorTypes
import com.example.identity.simulation.kobil.KobilSsms
import com.example.identity.simulation.kobil.KobilUserRef
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import com.example.identity.tools.auth_kobil.api.v1.KobilActivationStep

/**
 * toolId=enroll-kobil: provisions a user at KOBIL, mints the PIN this backend keeps, and turns the
 * bound device into an account credential. The PIN leaves the backend only here, for the SDK's
 * activation call, and in each login run after the client has unlocked.
 */
@Component
class EnrollKobilToolHandler(
    private val sessions: ToolSessionData,
    private val enrollmentRepository: KobilEnrollmentRepository,
    private val secrets: KobilSecrets,
    private val ssms: KobilSsms,
    private val pins: KobilPins,
    @Value("\${identity.kobil.tenant-id:identity-demo}") private val tenantId: String,
    private val clock: Clock,
) {

    /**
     * Called directly by EnrollKobilToolController. Everything the app needs for the SDK's
     * `ActivateEvent` is minted here in one go - there is no sensible half-provisioned state to
     * pause in, and a second round trip would only add one.
     */
    @Transactional
    fun start(toolSessionId: ToolSessionId): ToolOutcome {
        val user = ssms.provisionUser(tenantId, subjectRef = toolSessionId.toString())
        val activationCode = ssms.issueActivationCode(user)
        val pin = secrets.newPin()
        ssms.setPin(user, pin)
        val unlockSecret = secrets.newUnlockSecret()

        val session = EnrollKobilToolSession(
            kobilTenantId = user.tenantId,
            kobilUserId = user.userId,
            activationCode = activationCode,
            pin = pin,
            unlockSecret = unlockSecret,
        )
        sessions.save(toolSessionId, session)

        return outcomeFor(session)
    }

    /**
     * Called directly by EnrollKobilToolController (docs/08-projektrahmen.md A11).
     * [bindingKeyRef] is the enrolling channel's DPoP fingerprint, threaded through so later AUTH
     * candidate resolution offers this credential only on the installation that holds it.
     */
    @Transactional
    fun patch(
        toolSessionId: ToolSessionId,
        activated: Boolean?,
        biometricConsent: Boolean?,
        bindingKeyRef: String,
        label: String?,
        /** The key the kept PIN is sealed under (ADR-55); the context names it. */
        masterKeyId: MasterKeyId,
    ): ToolOutcome {
        val session = sessions.require<EnrollKobilToolSession>(toolSessionId)
        val user = KobilUserRef(session.kobilTenantId, session.kobilUserId)

        // The identifier is asked of KOBIL, never accepted from the client: it is the anchor every
        // later authentication is compared against, so it must not be something the caller chose.
        val deviceId = if (activated == true) ssms.deviceOf(user) else null

        return when (val decision = EnrollKobilFlow.decide(activated, biometricConsent, deviceId)) {
            is EnrollKobilDecision.Unchanged -> outcomeFor(session)

            is EnrollKobilDecision.Enroll -> {
                // Idempotent: a repeated confirmation of the same activation must reuse the row
                // rather than violate the UNIQUE constraint on kobil_user_id.
                val enrollment = enrollmentRepository.findByKobilUserId(session.kobilUserId)
                    ?: enrollmentRepository.save(
                        KobilEnrollment(
                            kobilTenantId = session.kobilTenantId,
                            kobilUserId = session.kobilUserId,
                            kobilDeviceId = decision.deviceId,
                            sealedPin = pins.seal(session.pin, masterKeyId),
                            masterKeyId = masterKeyId,
                            // Only with consent - see EnrollKobilDecision.Enroll.
                            unlockSecretHash = if (decision.biometricConsent) secrets.hash(session.unlockSecret) else null,
                            bindingKeyRef = bindingKeyRef,
                            label = label,
                            createdAt = clock.instant(),
                        )
                    )
                // The activation secrets have done their job: the PIN now lives in the credential,
                // the unlock secret only as its hash. Nothing keeps them in the tool session until
                // it ends, which may be up to 24 h away.
                sessions.save(toolSessionId, session.copy(activationCode = "", pin = "", unlockSecret = ""))

                ToolOutcome.Completed.Enrolled(
                    enrollmentRef = EnrollmentRef(type = KOBIL_ENROLLMENT_TYPE, id = enrollment.id.toString()),
                    amr = listOf(KobilModule.method, decision.userVerification.wireValue),
                    factorTypes = decision.userVerification.kobilFactorTypes(),
                    // The KOBIL user id is not repeated here: it lives in this module's own
                    // enrollment row, where the auth and cleanup paths read it. The device id is
                    // what the phone may show; the client cannot learn it any other way.
                    boundKeyRef = bindingKeyRef,
                    reference = decision.deviceId,
                    label = label,
                )
            }
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId): ToolOutcome {
        return outcomeFor(sessions.require<EnrollKobilToolSession>(toolSessionId))
    }

    /**
     * Rebuilt on every read on purpose: during setup the app still has to receive these values, so a
     * reload picks the flow up again. Once the credential exists, the PIN only comes back via `auth-kobil`.
     */
    private fun outcomeFor(session: EnrollKobilToolSession): ToolOutcome.InProgress {
        val (step, fields) = EnrollKobilState.describe()
        return ToolOutcome.InProgress(
            nextStep = step,
            stepData = KobilActivationStep(
                missingFields = fields,
                tenantId = session.kobilTenantId,
                kobilUserId = session.kobilUserId,
                activationCode = session.activationCode,
                // Handed over because the SDK's activation call takes it - the user never sees it.
                pin = session.pin,
                // The app stores this behind its biometric prompt; only its hash outlives setup.
                unlockSecret = session.unlockSecret,
            ),
        )
    }
}
