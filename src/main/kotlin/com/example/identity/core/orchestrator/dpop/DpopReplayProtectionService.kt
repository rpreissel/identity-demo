package com.example.identity.core.orchestrator.dpop

import org.springframework.dao.DataIntegrityViolationException
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant

/**
 * Single-use enforcement for DPoP and device proofs. Backed by a table, so the guarantee survives
 * restarts and holds across instances. The insert itself is the check: a duplicate primary key
 * raises, so two concurrent replays cannot both pass. The key is a digest of `thumbprint:jti`,
 * because the client chooses `jti`.
 */
@Component
class DpopReplayProtectionService(private val repository: DpopProofReplayRepository, private val clock: Clock) {

    /**
     * Its own transaction: this runs during argument resolution, and a duplicate-key violation must
     * not poison a surrounding transaction.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun validateAndStore(thumbprint: String, jti: String?, expiresAt: Instant) {
        val key = sha256Hex("$thumbprint:$jti")
        try {
            repository.insert(key, expiresAt)
        } catch (_: DataIntegrityViolationException) {
            throw DpopValidationException(DpopFailure.REPLAY)
        }
    }

    /**
     * An entry is useful only until its proof would be rejected as too old. Swept on a schedule,
     * so requests do not pay for it.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    @Transactional
    fun cleanupExpiredEntries() {
        repository.deleteExpired(clock.instant())
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
}
