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

    /**
     * The widths of the sealed columns for the Flyway placeholders, by mode (ADR-55): what the
     * ciphertext needs (nonce, value, tag), or the readable value plus its demo header.
     */
    fun schemaPlaceholders(): Map<String, String> = schemaPlaceholders(envelopes.encryptionEnabled)

    companion object {
        fun schemaPlaceholders(encrypted: Boolean): Map<String, String> {
            val allowance = if (encrypted) 0 else Envelopes.HEADER_ALLOWANCE
            return mapOf(
                "encryption_enabled" to encrypted.toString(),
                // A wrapped 32-byte key: 12 nonce + 32 + 16 tag.
                "wrapped_key_width" to (64 + allowance).toString(),
                "claim_value_width" to (1100 + allowance).toString(),
                // 64 hex characters of HMAC-SHA256, or the readable normalized value behind its header.
                "digest_width" to (if (encrypted) 64 else 300 + allowance).toString(),
                // Phone number, PIN, label, reference.
                "secret_width" to (256 + allowance).toString(),
                "details_width" to (2048 + allowance).toString(),
            )
        }
    }

    /** Whether the KMS behind the KEK is the demo's simulation (ADR-54). */
    fun kmsSimulated(): Boolean = wrapper.simulated

    /** KEK versions some account is wrapped with that no configured secret matches: those accounts cannot be read. */
    fun orphanedKekVersions(): Set<String> = masterKeys.kekVersions() - wrapper.knownVersions
}
