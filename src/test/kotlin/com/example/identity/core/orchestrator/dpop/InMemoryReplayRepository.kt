package com.example.identity.core.orchestrator.dpop

import io.mockk.every
import io.mockk.mockk
import org.springframework.dao.DataIntegrityViolationException

/**
 * The smallest stub that keeps replay detection real: a set plus the primary-key violation. The
 * service only calls `insert`, and the insert is the check. `saveAndFlush` merges instead of
 * inserting, so it must not be faked as one; `DpopReplayProtectionDbTest` covers the real repository.
 */
fun inMemoryReplayRepository(): DpopProofReplayRepository {
    val seen = mutableSetOf<String>()
    val repository = mockk<DpopProofReplayRepository>()
    every { repository.insert(any(), any()) } answers {
        val proofHash = firstArg<String>()
        if (!seen.add(proofHash)) {
            throw DataIntegrityViolationException("duplicate proof_hash $proofHash")
        }
    }
    return repository
}
