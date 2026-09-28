package com.example.identity.core.orchestrator.dpop

import com.nimbusds.jose.jwk.JWK
import java.time.Instant

data class DpopProof(
    val token: String,
    val publicKey: JWK,
    val jti: String,
    val htm: String,
    val htu: String,
    val issuedAt: Instant,
    val nonce: String?
)

/**
 * Why a DPoP or device proof was rejected. The 401 response names only this code; what the server
 * saw (algorithm, key type, claim values) stays in the log, so a caller learns the rule, not more.
 */
enum class DpopFailure {
    MISSING,
    MALFORMED,
    WRONG_TYPE,
    UNSUPPORTED_ALGORITHM,
    INVALID_KEY,
    INVALID_SIGNATURE,
    INVALID_CLAIMS,
    HTM_MISMATCH,
    HTU_MISMATCH,
    IAT_MISSING,
    IAT_IN_FUTURE,
    IAT_TOO_OLD,
    JTI_MISSING,
    REPLAY
}

/** [detail] is for the log only. */
class DpopValidationException(
    val failure: DpopFailure,
    detail: String? = null,
    cause: Throwable? = null
) : RuntimeException(listOfNotNull(failure.name, detail).joinToString(": "), cause)
