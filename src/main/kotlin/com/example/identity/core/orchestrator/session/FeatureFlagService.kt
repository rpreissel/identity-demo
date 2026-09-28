package com.example.identity.core.orchestrator.session

import com.example.identity.core.orchestrator.domain.FeatureFlagProvider
import org.springframework.data.repository.findByIdOrNull
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock

/**
 * Read live, never cached, like `ToolAvailabilityService`: a flip applies to the next new journey.
 * As [FeatureFlagProvider] the flags reach every journey context generically, so a new flag only
 * needs its name in [com.example.identity.core.orchestrator.domain.FeatureFlags] and a strategy reading it.
 */
@Service
@Transactional
class FeatureFlagService(
    private val repository: FeatureFlagRepository,
    private val clock: Clock
) : FeatureFlagProvider {

    /** No row means off, the flag's default. */
    fun isEnabled(flagKey: String): Boolean = repository.findByIdOrNull(flagKey)?.enabled ?: false

    override fun activeFlags(): Set<String> =
        repository.findByEnabledTrue().mapNotNull { it.flagKey }.toSet()

    fun setEnabled(flagKey: String, enabled: Boolean, reason: String? = null) {
        val flag = repository.findByIdOrNull(flagKey) ?: FeatureFlag(flagKey = flagKey)
        flag.enabled = enabled
        flag.reason = reason
        flag.updatedAt = clock.instant()
        repository.save(flag)
    }
}
