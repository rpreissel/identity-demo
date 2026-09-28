package com.example.identity.tools.auth_kobil.internal.authkobil

import com.example.identity.contract.tool_api.UnresolvableReferenceException
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_kobil.AuthKobilDescriptor
import com.example.identity.tools.auth_kobil.api.v1.KobilUnlockCredential
import com.example.identity.tools.auth_kobil.internal.KobilEnrollment
import com.example.identity.tools.auth_kobil.internal.KobilEnrollmentRepository
import com.example.identity.tools.auth_kobil.internal.KobilSecrets
import com.example.identity.tools.auth_kobil.internal.kobilFactorTypes
import com.example.identity.simulation.kobil.KobilRisk
import com.example.identity.simulation.kobil.KobilSsms
import com.example.identity.simulation.kobil.KobilUserRef
import com.example.identity.contract.tool_api.credentials.PasswordCredentialPort
import com.example.identity.contract.tool_api.device.UserVerification
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolOutcome
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID
import com.example.identity.tools.auth_kobil.api.v1.KobilOtpStep

/**
 * toolId=auth-kobil: releases the backend-held PIN to an app that has unlocked locally, then proves
 * the device by redeeming the OTP the app got from KOBIL. The client carries only a reference; the
 * backend fetches the assertion itself, so a tampered client cannot assert an outcome.
 */
@Component
class AuthKobilToolHandler(
    private val descriptor: AuthKobilDescriptor,
    private val toolDataRepository: AuthKobilToolSessionRepository,
    private val enrollmentRepository: KobilEnrollmentRepository,
    private val secrets: KobilSecrets,
    private val ssms: KobilSsms,
    private val passwordCredentials: PasswordCredentialPort,
    /**
     * Which reported signals this deployment refuses to authenticate through. A named set, not a
     * score: a score would have to be invented, and an invented number reads as a measurement.
     */
    @Value("\${identity.kobil.blocking-risks:ROOTED,EMULATOR,DEBUGGER_ATTACHED,APP_TAMPERED}")
    private val blockingRisks: Set<KobilRisk>,
    @Value("\${identity.kobil.pin-release-ttl-seconds:120}") private val pinReleaseTtlSeconds: Long,
) {

    /**
     * @param passwordAvailable whether the account still holds an active password credential -
     * resolved by the controller, because only the orchestrator side can see the account's other
     * methods. It decides whether the password unlock is offered at all.
     */
    @Transactional
    fun start(toolSessionId: UUID, enrollmentRef: EnrollmentRef, passwordAvailable: Boolean): ToolOutcome {
        val session = toolDataRepository.save(
            AuthKobilToolSession(
                toolSessionId = toolSessionId,
                enrollmentRefId = enrollmentRef.id,
            )
        )
        return inProgress(stateOf(session, passwordAvailable))
    }

    /**
     * Hands the PIN over once, in this response only. The step state is rebuilt on every read, so
     * the PIN never goes there.
     *
     * @param passwordEnrollment null if the account has none. [PasswordCredentialPort.verify] runs
     * either way, so a missing password costs as much as a wrong one.
     */
    @Transactional
    fun releasePin(
        toolSessionId: UUID,
        unlock: KobilUnlockCredential,
        passwordEnrollment: EnrollmentRef?,
    ): ToolOutcome {
        val session = loadSession(toolSessionId)
        val enrollment = loadEnrollment(session)

        val unlocked = when (unlock) {
            is KobilUnlockCredential.BiometricUnlock ->
                secrets.matches(unlock.unlockSecret, enrollment.unlockSecretHash)
            is KobilUnlockCredential.PasswordUnlock ->
                passwordCredentials.verify(passwordEnrollment, unlock.password)
        }

        // One wording for every way this can fail - wrong secret, wrong password, or no password
        // credential at all. Telling them apart would answer questions about the account that the
        // caller has not proven a right to ask. A repeated release is not a failure: an app whose
        // window closed simply unlocks again, and the new release replaces the old one.
        if (!unlocked) {
            return ToolOutcome.Failed.IdentifiedAuth(Text("Entsperren fehlgeschlagen"))
        }

        session.release(unlock.userVerification, Instant.now().plusSeconds(pinReleaseTtlSeconds))

        val (step, fields) = AuthKobilState.AwaitingOtp(enrollment.kobilTenantId, enrollment.kobilUserId).describe()
        return ToolOutcome.InProgress(
            nextStep = step,
            // The released PIN belongs to this one response - see KobilOtpStep.kobilPin.
            stepData = (fields as KobilOtpStep).copy(kobilPin = enrollment.pin),
        )
    }

    /**
     * The step this session is in, derived from whether a release still counts - never stored
     * twice. A released PIN is not part of it: that value belongs to one response only.
     */
    private fun stateOf(session: AuthKobilToolSession, passwordAvailable: Boolean): AuthKobilState {
        val enrollment = loadEnrollment(session)
        if (session.liveRelease(Instant.now()) != null) {
            return AuthKobilState.AwaitingOtp(enrollment.kobilTenantId, enrollment.kobilUserId)
        }
        // A stored hash exists exactly when biometrics was consented to; the password counts while
        // the account has one. An empty list is possible, and the client can say so.
        val options = buildList {
            if (enrollment.unlockSecretHash != null) add(UserVerification.BIOMETRIC.wireValue)
            if (passwordAvailable) add("password")
        }
        return AuthKobilState.Unlock(enrollment.kobilTenantId, enrollment.kobilUserId, options)
    }

    private fun inProgress(state: AuthKobilState): ToolOutcome.InProgress {
        val (step, fields) = state.describe()
        return ToolOutcome.InProgress(nextStep = step, stepData = fields)
    }

    /** Redeems the one-time password at KOBIL and decides on the assertion behind it. */
    @Transactional
    fun patch(toolSessionId: UUID, otp: String?): ToolOutcome {
        val session = loadSession(toolSessionId)
        val enrollment = loadEnrollment(session)
        val release = session.liveRelease(Instant.now())

        val verification = if (release != null && otp != null) {
            ssms.verifyOtp(KobilUserRef(enrollment.kobilTenantId, enrollment.kobilUserId), otp)
        } else {
            null
        }

        return when (val decision = AuthKobilFlow.decide(verification, enrollment.kobilDeviceId, release, blockingRisks)) {
            is AuthKobilDecision.NotReleased ->
                ToolOutcome.Failed.IdentifiedAuth(Text("Entsperren erforderlich"))

            is AuthKobilDecision.OtpInvalid ->
                ToolOutcome.Failed.IdentifiedAuth(Text("Bestaetigung nicht erkannt"))

            // Same wording auth-device uses: it must not reveal which device was expected.
            is AuthKobilDecision.WrongDevice ->
                ToolOutcome.Failed.IdentifiedAuth(Text("Geraet nicht erkannt"))

            // Deliberately its own reason. This is not a user's slip but a statement about the
            // device; folding it into "not recognized" would swallow a real finding.
            is AuthKobilDecision.RiskRejected ->
                ToolOutcome.Failed.IdentifiedAuth(Text("Geraet als unsicher gemeldet"))

            // Authenticated carries no evidence blob, so non-blocking signals (e.g. OS_OUTDATED)
            // go unrecorded.
            is AuthKobilDecision.Complete -> ToolOutcome.Completed.Authenticated(
                amr = listOf(descriptor.method, decision.userVerification.wireValue),
                achievedAcr = descriptor.maxAcr,
                factorTypes = decision.userVerification.kobilFactorTypes(),
            )
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: UUID, passwordAvailable: Boolean): ToolOutcome =
        inProgress(stateOf(loadSession(toolSessionId), passwordAvailable))

    private fun loadSession(toolSessionId: UUID): AuthKobilToolSession =
        checkNotNull(toolDataRepository.findByIdOrNull(toolSessionId)) {
            "Unknown auth-kobil tool session: $toolSessionId"
        }

    /** Gone (removed on another channel) or never there: no wrong guess, nothing to count, as in auth-sms. */
    private fun loadEnrollment(session: AuthKobilToolSession): KobilEnrollment =
        session.enrollmentRefId?.toLongOrNull()?.let { enrollmentRepository.findByIdOrNull(it) }
            ?: throw UnresolvableReferenceException(Text("Anmeldeverfahren nicht gefunden"), "toolSession=${session.toolSessionId}")
}
