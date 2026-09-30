package com.example.identity.core.orchestrator.domain.policy

import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.FactorType

/** [AuthEvidence.from] for proofs made just now on [com.example.identity.TEST_CLOCK]. */
fun AuthEvidence.Companion.fromNow(
    amr: List<String>,
    factorTypes: Set<FactorType>,
    methodAcr: Map<String, String> = emptyMap(),
    enrolledUnderAcr: Map<String, String> = emptyMap(),
    source: Map<String, String> = emptyMap(),
    amrSourceId: Map<String, String> = emptyMap(),
    axis: Map<String, EvidenceAxis> = emptyMap(),
): AuthEvidence = from(amr, factorTypes, methodAcr, enrolledUnderAcr, source, amrSourceId, axis).provenAt()

/** The same evidence, every proof stamped with [at]. */
fun AuthEvidence.provenAt(at: java.time.Instant = TEST_NOW): AuthEvidence = AuthEvidence(factors.map { it.copy(provenAt = at) })
