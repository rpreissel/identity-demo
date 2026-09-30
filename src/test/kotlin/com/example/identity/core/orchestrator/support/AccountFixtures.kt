package com.example.identity.core.orchestrator.support

import com.example.identity.core.account.AccountService
import com.example.identity.tools.auth_device.internal.DeviceEnrollment
import com.example.identity.tools.auth_device.internal.DeviceEnrollmentRepository
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollment
import com.example.identity.tools.auth_sms.internal.AuthSmsEnrollmentRepository
import com.example.identity.core.orchestrator.session.SessionManagementService
import com.example.identity.contract.tool_api.directory.EMAIL_ANCHOR_ENROLLMENT
import com.example.identity.contract.tool_api.credentials.PasswordCredentialPort
import com.example.identity.contract.tool_api.directory.PersonDirectory
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.claims.AttributeType
import com.example.identity.contract.tool_api.claims.Claim
import com.example.identity.contract.tool_api.claims.ClaimSource
import com.example.identity.contract.tool_api.EnrollmentRef
import com.example.identity.contract.tool_api.ToolId
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import java.time.Instant

/**
 * Builds the account an integration test needs as a precondition, through the domain services
 * instead of the registration click path. Only the registration suites test the step order; the
 * others need the result, independent of it. Not raw SQL: [AccountService] keeps the invariants,
 * so a fixture cannot build a state the flow could never produce. The values mirror a real run.
 */
@Component
class AccountFixtures(
    private val accountService: AccountService,
    private val personDirectory: PersonDirectory,
    private val passwordCredentialPort: PasswordCredentialPort,
    private val smsEnrollmentRepository: AuthSmsEnrollmentRepository,
    private val deviceEnrollmentRepository: DeviceEnrollmentRepository,
    private val sessionManagementService: SessionManagementService
) {

    /** A login method an account can be seeded with, in the shape the enrollment tools leave behind. */
    sealed interface Method {
        /** The `sms` method plus its auth_sms enrollment row. */
        data class Sms(val phoneNumber: String = PHONE_NUMBER) : Method

        /** The `password` method plus its auth_password enrollment row (hashed via the real port). */
        data class Password(val password: String = DEMO_PASSWORD) : Method

        /**
         * The `email` login method only (ADR-17). The confirmed address is an anchor, seeded by
         * [seedAccount]'s `email` parameter.
         */
        data object Email : Method

        /**
         * The `device` method plus its auth_device enrollment row. [thumbprint] must match the
         * test's DPoP key, otherwise the descriptor's `keyBinding` does not offer it.
         */
        data class Device(val thumbprint: String, val label: String? = null) : Method
    }

    /**
     * Seeds one account and returns its id.
     *
     * @param email the confirmed address (an EMAIL anchor, as `confirm-email` leaves it), or `null`.
     * @param bindDeviceKeyRef binds the account to this device (DeviceAccountLink), so a fresh
     * channel on the same key is recognised. `null` leaves it unbound.
     */
    @Transactional
    fun seedAccount(
        kvnr: String = KVNR,
        name: String = NAME,
        vorname: String = VORNAME,
        email: String? = EMAIL,
        methods: List<Method> = emptyList(),
        bindDeviceKeyRef: String? = null,
        /** The eID card anchor (ADR-19) as `ident-eid` writes it, or `null` without a card. */
        restrictedId: String? = null
    ): Long {
        val accountId = accountService.createAccountInSetup().accountId
        identify(accountId, kvnr, name, vorname)
        if (email != null) confirmEmail(accountId, email)
        if (restrictedId != null) {
            accountService.recordClaims(
                accountId,
                listOf(Claim(AttributeType.EID_RESTRICTED_ID, restrictedId, ClaimSource.of(ToolId("ident-eid")), IDENT_ACR)),
                provenAcr = IDENT_ACR
            )
        }
        methods.forEach { addMethod(accountId, it) }
        if (bindDeviceKeyRef != null) sessionManagementService.linkDeviceToAccount(bindDeviceKeyRef, accountId)
        return accountId
    }

    /** What a completed ident-fsc run leaves behind: the four stammdaten claims plus the audit row. */
    private fun identify(accountId: Long, kvnr: String, name: String, vorname: String) {
        val personId = requireNotNull(personDirectory.findPersonIdByKvnr(kvnr)) {
            "No test person for kvnr $kvnr - see demo_seed/V16__testdata.sql"
        }
        accountService.recordClaims(
            accountId,
            listOf(
                Claim(AttributeType.PERSON_ID, personId, ClaimSource.PERSON_DIRECTORY, IDENT_ACR),
                Claim(AttributeType.KVNR, kvnr, ClaimSource.PERSON_DIRECTORY, IDENT_ACR),
                Claim(AttributeType.FAMILY_NAME, name, ClaimSource.PERSON_DIRECTORY, IDENT_ACR),
                Claim(AttributeType.GIVEN_NAMES, vorname, ClaimSource.PERSON_DIRECTORY, IDENT_ACR)
            ),
            provenAcr = IDENT_ACR
        )
        accountService.addIdentification(accountId, "fsc", IDENT_ACR.value, role = "IDENTIFICATION")
    }

    /** What confirm-email leaves behind: an EMAIL anchor and no login method (ADR-17). */
    private fun confirmEmail(accountId: Long, email: String) {
        accountService.recordClaims(
            accountId,
            listOf(Claim(AttributeType.EMAIL, email, CONFIRM_EMAIL_SOURCE, AcrLevel.LOA1)),
            provenAcr = ENROLLED_UNDER_ACR
        )
    }

    private fun addMethod(accountId: Long, method: Method) {
        val instanceId = UUID.randomUUID()
        when (method) {
            is Method.Sms -> {
                val enrollment = smsEnrollmentRepository.save(AuthSmsEnrollment(phoneNumber = method.phoneNumber, createdAt = Instant.now()))
                accountService.recordClaims(
                    accountId,
                    listOf(Claim(AttributeType.PHONE_NUMBER, method.phoneNumber, ENROLL_SMS_SOURCE, AcrLevel.LOA1)),
                    provenAcr = ENROLLED_UNDER_ACR,
                    authMethodId = instanceId
                )
                accountService.addAuthenticationMethod(
                    accountId, "sms",
                    EnrollmentRef("auth_sms.enrollment", enrollment.id.toString()),
                    enrolledUnderAcr = ENROLLED_UNDER_ACR.value,
                    details = emptyMap(),
                    enrolledUnderAmr = listOf("fsc"),
                    instanceId = instanceId
                )
            }

            is Method.Password -> accountService.addAuthenticationMethod(
                accountId, "password",
                passwordCredentialPort.setNew(method.password),
                enrolledUnderAcr = ENROLLED_UNDER_ACR.value,
                details = emptyMap(),
                enrolledUnderAmr = listOf("fsc"),
                instanceId = instanceId
            )

            is Method.Email -> accountService.addAuthenticationMethod(
                accountId, "email", EMAIL_ANCHOR_ENROLLMENT,
                enrolledUnderAcr = ENROLLED_UNDER_ACR.value,
                details = emptyMap(),
                enrolledUnderAmr = listOf("fsc"),
                instanceId = instanceId
            )

            is Method.Device -> {
                val enrollment = deviceEnrollmentRepository.save(DeviceEnrollment(thumbprint = method.thumbprint, createdAt = Instant.now()))
                accountService.addAuthenticationMethod(
                    accountId, "device",
                    EnrollmentRef("auth_device.enrollment", enrollment.id.toString()),
                    enrolledUnderAcr = ENROLLED_UNDER_ACR.value,
                    details = mapOf("deviceBindingKeyRef" to method.thumbprint),
                    enrolledUnderAmr = listOf("fsc"),
                    allowsMultipleInstances = true,
                    label = method.label,
                    instanceId = instanceId
                )
            }
        }
    }

    companion object {
        const val KVNR = "A123456789"
        const val NAME = "Muster"
        const val VORNAME = "Max"
        const val EMAIL = "max.mustermann@example.com"
        const val PHONE_NUMBER = "+491701234567"
        const val DEMO_PASSWORD = "correct-horse-battery"

        /** ident-fsc's own achieved level - what the stammdaten claims are proven with. */
        private val IDENT_ACR = AcrLevel.LOA2

        /**
         * What enrollments during a registration are paid for: the evidence established so far
         * (the identification's loa2), not the enrolling tool's loa1 ceiling.
         */
        private val ENROLLED_UNDER_ACR = AcrLevel.LOA2

        private val CONFIRM_EMAIL_SOURCE = ClaimSource("confirm-email")
        private val ENROLL_SMS_SOURCE = ClaimSource("enroll-sms")
    }
}
