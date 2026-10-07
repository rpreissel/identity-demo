package com.example.identity.core.account

import com.example.identity.core.account.application.Envelopes
import com.example.identity.core.account.application.MasterKeyWrapper
import com.example.identity.core.account.infrastructure.MasterKeyRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

/**
 * What `ProductionModeCheck` may ask about the claim log's key-encryption key (ADR-52), without
 * reaching into the module. Whether a finding stops the start is that check's decision.
 */
@Component
@Transactional(readOnly = true)
class ClaimEncryptionKeys(
    private val wrapper: MasterKeyWrapper,
    private val masterKeys: MasterKeyRepository,
    private val envelopes: Envelopes,
) {
    /** Whether values are stored readable (`identity.encryption.enabled=false`, ADR-55) - a demo setting only. */
    fun encryptionDisabled(): Boolean = !envelopes.encryptionEnabled

    /** Whether the KMS behind the KEK is the demo's simulation (ADR-54). */
    fun kmsSimulated(): Boolean = wrapper.simulated

    /** KEK versions some account is wrapped with that no configured secret matches: those accounts cannot be read. */
    fun orphanedKekVersions(): Set<String> = masterKeys.kekVersions() - wrapper.knownVersions
}
