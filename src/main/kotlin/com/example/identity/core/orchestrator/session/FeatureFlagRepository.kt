package com.example.identity.core.orchestrator.session

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface FeatureFlagRepository : JpaRepository<FeatureFlag, String> {
    fun findByEnabledTrue(): List<FeatureFlag>
}
