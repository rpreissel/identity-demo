package com.example.identity.core.orchestrator.domain.journey.state

/**
 * Why a signed-in session must prove itself again: the action that asks (FRESH_PROOF, and the
 * step-up it may need on the way). A value, not text: states are persisted, and wording must change
 * without touching stored journeys.
 */
enum class ProofPurpose(
    /**
     * Whether a dead end may fall back to a fresh identification (`RE_IDENTIFY`). True by default,
     * because re-identification is an equally valid path to loa2 (docs/04-orchestrierung.md #4).
     */
    val allowsReIdentification: Boolean = true,
) {
    DELETE_ACCOUNT,
    MANAGE_METHODS,

    /** A peer approval must never let someone acquire a fresh identity just to confirm someone else's login. */
    PEER_LOGIN(allowsReIdentification = false),
}
