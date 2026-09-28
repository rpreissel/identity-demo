package com.example.identity.core.orchestrator.domain

/**
 * Every error this API answers with, together with its HTTP status (docs/07-betrieb.md #1).
 *
 * The status belongs to the code, not to the throw site, so the pairing cannot be written wrongly.
 * A plain `Int` keeps Spring's `HttpStatus` out of this package (ADR-40).
 *
 * Part of the wire contract: the names appear in `ErrorResponse.error`, and a client may branch on
 * them (the frontend retries `CONCURRENT_MODIFICATION` once). A client must still expect a code it
 * does not know - handle it by its HTTP status.
 */
enum class ErrorCode(val httpStatus: Int) {
    /** The request itself is malformed or a value in it is invalid. */
    BAD_REQUEST(400),
    /** Missing or invalid DPoP proof, peer-auth assertion or token. */
    UNAUTHORIZED(401),
    /** The caller's key is not the one this channel is bound to. */
    BINDING_MISMATCH(403),
    NOT_FOUND(404),
    /** The action does not fit the current state - a real conflict, not a bug. */
    INVALID_STATE_TRANSITION(409),
    /** Another request changed the same session at the same time; retrying is safe. */
    CONCURRENT_MODIFICATION(409),
    /** The process expired, was consumed, or ran out of retries. */
    PROCESS_GONE(410),
    /** The required level cannot be reached with the account's methods. */
    PROCESS_ABORTED(410),
    /** A reference that should resolve does not (e.g. an enrollment that no longer exists). */
    UNRESOLVABLE_REFERENCE(422),
    ACCOUNT_LOCKED(423),
    TOO_MANY_REQUESTS(429),
    /** Something that should not happen did. Details are in the server log, not in the response. */
    INTERNAL_ERROR(500),
}
