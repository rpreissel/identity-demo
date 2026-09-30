package com.example.identity.core.orchestrator.domain.policy

import com.example.identity.TEST_NOW
import com.example.identity.contract.tool_api.FactorType

/** [SessionEvidence.from] for proofs made just now on [com.example.identity.TEST_CLOCK]. */
fun SessionEvidence.Companion.fromNow(
    amr: List<String>,
    factorTypes: Set<FactorType>,
    methodAcr: Map<String, String> = emptyMap(),
    enrolledUnderAcr: Map<String, String> = emptyMap(),
    source: Map<String, String> = emptyMap(),
    amrSourceId: Map<String, String> = emptyMap(),
    axis: Map<String, EvidenceAxis> = emptyMap(),
): SessionEvidence = from(amr, factorTypes, methodAcr, enrolledUnderAcr, source, amrSourceId, axis).provenAt()

/** The same evidence, every proof stamped with [at]. */
fun SessionEvidence.provenAt(at: java.time.Instant = TEST_NOW): SessionEvidence = SessionEvidence(methods.map { it.copy(provenAt = at) })
