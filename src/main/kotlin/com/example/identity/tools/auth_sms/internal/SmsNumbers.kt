package com.example.identity.tools.auth_sms.internal

import com.example.identity.contract.tool_api.ids.MasterKeyId
import com.example.identity.contract.tool_api.kms.AccountSealing
import java.time.Instant
import org.springframework.stereotype.Component

/** The phone number of an enrollment at rest: sealed under the journey's master key (ADR-55), read back by the key the row names. */
@Component
class SmsNumbers(private val sealing: AccountSealing) {

    fun newEnrollment(phoneNumber: String, masterKeyId: MasterKeyId, createdAt: Instant): AuthSmsEnrollment =
        AuthSmsEnrollment(sealedPhoneNumber = sealing.forKey(masterKeyId).seal(PURPOSE, phoneNumber.toByteArray()), masterKeyId = masterKeyId, createdAt = createdAt)

    fun phoneNumberOf(enrollment: AuthSmsEnrollment): String =
        String(sealing.forKey(checkNotNull(enrollment.masterKeyId)).open(PURPOSE, checkNotNull(enrollment.sealedPhoneNumber)))

    private companion object {
        const val PURPOSE = "auth-sms:phone-number"
    }
}
