package com.example.identity.core.account

import com.example.identity.core.account.application.MasterKeyWrapper
import org.springframework.stereotype.Component

/**
 * What `ProductionModeCheck` may ask about the claim log's key-encryption key (ADR-52), without
 * reaching into the module. Whether a finding stops the start is that check's decision.
 */
@Component
class ClaimEncryptionKeys(private val wrapper: MasterKeyWrapper) {
    /** Whether the KEK is the public value `application.yml` ships for the demo. */
    fun usesDemoKek(): Boolean = wrapper.usesDemoKek
}
