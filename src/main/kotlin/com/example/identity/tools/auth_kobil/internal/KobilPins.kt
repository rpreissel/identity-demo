package com.example.identity.tools.auth_kobil.internal

import com.example.identity.contract.tool_api.ids.MasterKeyId
import com.example.identity.contract.tool_api.kms.AccountSealing
import org.springframework.stereotype.Component

/**
 * The kept PIN at rest (ADR-21): sealed under the journey's master key (ADR-55), read back by the
 * key the row names. The server still reads it on every release, which is what ADR-21 asks for;
 * what changes is that the table alone no longer yields it.
 */
@Component
class KobilPins(private val sealing: AccountSealing) {

    fun seal(pin: String, masterKeyId: MasterKeyId): ByteArray = sealing.forKey(masterKeyId).seal(PURPOSE, pin.toByteArray())

    fun pinOf(enrollment: KobilEnrollment): String =
        String(sealing.forKey(checkNotNull(enrollment.masterKeyId)).open(PURPOSE, checkNotNull(enrollment.sealedPin)))

    private companion object {
        const val PURPOSE = "auth-kobil:pin"
    }
}
