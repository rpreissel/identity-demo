package com.example.identity.core.orchestrator.session

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * One runtime feature flag, keyed by [com.example.identity.core.orchestrator.domain.JourneyFeatureFlag.key]
 * or a [com.example.identity.core.orchestrator.keycloak.KeycloakFeatureFlags] name,
 * switchable without a redeploy. No row means off, so a new flag needs no seeding (as
 * [com.example.identity.core.orchestrator.tool.ToolAvailability]). One generic table, not a table per flag:
 * a flag is operational state. Its meaning lives in the constant and the strategy reading it.
 */
@Entity
@Table(schema = "orchestrator", name = "feature_flag")
class FeatureFlag(
    @Id
    @Column(name = "flag_key", nullable = false, length = 100)
    var flagKey: String? = null
) {
    @Column(name = "enabled", nullable = false)
    var enabled: Boolean = false

    /** Why it was flipped: context for the next operator, never evaluated. */
    @Column(name = "reason", length = 255)
    var reason: String? = null

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant? = null
}
