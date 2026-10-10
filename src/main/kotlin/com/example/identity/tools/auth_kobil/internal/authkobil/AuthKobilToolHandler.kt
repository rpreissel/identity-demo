package com.example.identity.tools.auth_kobil.internal.authkobil

import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.credentials.requireEnrollment
import com.example.identity.contract.tool_api.require
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.tools.auth_kobil.KobilModule
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import com.example.identity.contract.texts.Text
import com.example.identity.tools.auth_kobil.internal.KOBIL_ENROLLMENT_TYPE
import com.example.identity.tools.auth_kobil.api.v1.KobilUnlockCredential
import com.example.identity.tools.auth_kobil.internal.KobilEnrollment
import com.example.identity.tools.auth_kobil.internal.KobilEnrollmentRepository
import com.example.identity.tools.auth_kobil.internal.KobilPins
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
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import com.example.identity.tools.auth_kobil.api.v1.KobilOtpStep

/**
 * toolId=auth-kobil: releases the backend-held PIN to an app that has unlocked locally, then proves
 * the device by redeeming the OTP the app got from KOBIL. The client carries only a reference; the
 * backend fetches the assertion itself, so a tampered client cannot assert an outcome.
 */
@Component
class AuthKobilToolHandler(
    private val sessions: ToolSessionData,
    private val enrollmentRepository: KobilEnrollmentRepository,
    private val secrets: KobilSecrets,
    private val ssms: KobilSsms,
    private val passwordCredentials: PasswordCredentialPort,
    private val pins: KobilPins,
    /**
     * Which reported signals this deployment refuses to authenticate through. A named set, not a
     * score: a score would have to be invented, and an invented number reads as a measurement.
     */
    @Value("\${identity.kobil.blocking-risks:ROOTED,EMULATOR,DEBUGGER_ATTACHED,APP_TAMPERED}")
    private val blockingRisks: Set<KobilRisk>,
    @Value("\${identity.kobil.pin-release-ttl-seconds:120}") private val pinReleaseTtlSeconds: Long,
    private val clock: Clock,
) {

    /**
     * @param passwordAvailable whether the account still holds an active password credential -
     * resolved by the controller, because only the orchestrator side can see the account's other
     * methods. It decides whether the password unlock is offered at all.
     */
    @Transactional
    fun start(toolSessionId: ToolSessionId, enrollmentRef: EnrollmentRef, passwordAvailable: Boolean): ToolOutcome {
        if (enrollmentRef.type != KOBIL_ENROLLMENT_TYPE) {
            throw UnresolvableReferenceException(Text("Unerwarteter Enrollment-Typ"), "type=${enrollmentRef.type}")
        }
        val session = AuthKobilToolSession(enrollmentRefId = enrollmentRef.id)
        sessions.save(toolSessionId, session)
        return inProgress(stateOf(toolSessionId, session, passwordAvailable))
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
        toolSessionId: ToolSessionId,
        unlock: KobilUnlockCredential,
        passwordEnrollment: EnrollmentRef?,
    ): ToolOutcome {
        val session = loadSession(toolSessionId)
        val enrollment = loadEnrollment(toolSessionId, session)

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
            return ToolOutcome.Failed.KnownAccountAuth(Text("Entsperren fehlgeschlagen"))
        }

        sessions.save(toolSessionId, session.released(unlock.userVerification, clock.instant().plusSeconds(pinReleaseTtlSeconds)))

        val (step, fields) = AuthKobilState.AwaitingOtp(enrollment.kobilTenantId, enrollment.kobilUserId).describe()
        return ToolOutcome.InProgress(
            nextStep = step,
            // The released PIN belongs to this one response - see KobilOtpStep.kobilPin.
            stepData = (fields as KobilOtpStep).copy(kobilPin = pins.pinOf(enrollment)),
        )
    }

    /**
     * The step this session is in, derived from whether a release still counts - never stored
     * twice. A released PIN is not part of it: that value belongs to one response only.
     */
    private fun stateOf(toolSessionId: ToolSessionId, session: AuthKobilToolSession, passwordAvailable: Boolean): AuthKobilState {
        val enrollment = loadEnrollment(toolSessionId, session)
        if (session.liveRelease(clock.instant()) != null) {
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
    fun patch(toolSessionId: ToolSessionId, otp: String?): ToolOutcome {
        val session = loadSession(toolSessionId)
        val enrollment = loadEnrollment(toolSessionId, session)
        val release = session.liveRelease(clock.instant())

        val verification = if (release != null && otp != null) {
            ssms.verifyOtp(KobilUserRef(enrollment.kobilTenantId, enrollment.kobilUserId), otp)
        } else {
            null
        }

        return when (val decision = AuthKobilFlow.decide(verification, enrollment.kobilDeviceId, release, blockingRisks)) {
            is AuthKobilDecision.NotReleased ->
                ToolOutcome.Failed.KnownAccountAuth(Text("Entsperren erforderlich"))

            is AuthKobilDecision.OtpInvalid ->
                ToolOutcome.Failed.KnownAccountAuth(Text("Bestaetigung nicht erkannt"))

            // Same wording auth-device uses: it must not reveal which device was expected.
            is AuthKobilDecision.WrongDevice ->
                ToolOutcome.Failed.KnownAccountAuth(Text("Geraet nicht erkannt"))

            // Deliberately its own reason. This is not a user's slip but a statement about the
            // device; folding it into "not recognized" would swallow a real finding.
            is AuthKobilDecision.RiskRejected ->
                ToolOutcome.Failed.KnownAccountAuth(Text("Geraet als unsicher gemeldet"))

            // Authenticated carries no evidence blob, so non-blocking signals (e.g. OS_OUTDATED)
            // go unrecorded.
            is AuthKobilDecision.Complete -> ToolOutcome.Completed.Authenticated(
                amr = listOf(KobilModule.method, decision.userVerification.wireValue),
                factorTypes = decision.userVerification.kobilFactorTypes(),
            )
        }
    }

    @Transactional(readOnly = true)
    fun read(toolSessionId: ToolSessionId, passwordAvailable: Boolean): ToolOutcome =
        inProgress(stateOf(toolSessionId, loadSession(toolSessionId), passwordAvailable))

    private fun loadSession(toolSessionId: ToolSessionId): AuthKobilToolSession =
        sessions.require<AuthKobilToolSession>(toolSessionId)

    private fun loadEnrollment(toolSessionId: ToolSessionId, session: AuthKobilToolSession): KobilEnrollment =
        enrollmentRepository.requireEnrollment(session.enrollmentRefId, toolSessionId)
}
