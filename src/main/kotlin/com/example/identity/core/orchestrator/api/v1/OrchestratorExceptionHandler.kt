package com.example.identity.core.orchestrator.api.v1

import com.example.identity.core.orchestrator.journey.JourneyEndedException
import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.dpop.DpopFailure
import com.example.identity.core.orchestrator.dpop.DpopValidationException
import com.example.identity.core.orchestrator.keycloak.PeerAuthValidationException
import com.example.identity.core.orchestrator.session.ChannelSessionEndedException
import com.example.identity.contract.tool_api.directory.IdentityConflictException
import com.example.identity.contract.tool_api.InvalidInputException
import com.example.identity.contract.tool_api.TooManyRequestsException
import com.example.identity.contract.tool_api.InvalidStateException
import com.example.identity.contract.tool_api.UnresolvableReferenceException
import org.hibernate.exception.ConstraintViolationException
import org.slf4j.LoggerFactory
import org.springframework.http.ResponseEntity
import org.springframework.http.converter.HttpMessageNotReadableException
import org.springframework.orm.ObjectOptimisticLockingFailureException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import java.util.Locale
import com.example.identity.core.orchestrator.domain.ErrorCode
import com.example.identity.core.orchestrator.domain.OrchestratorException
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException

/** Maps the error contract from docs/07-betrieb.md #1 onto exceptions raised anywhere in the call chain. */
@RestControllerAdvice
class OrchestratorExceptionHandler {

    /**
     * Missing/invalid DPoP or device proof: 401 (docs/07-betrieb.md #1). The response names the
     * fixed [DpopFailure] only, so a client can tell a skewed clock from a broken key; the detail
     * goes to the log.
     */
    @ExceptionHandler(DpopValidationException::class)
    fun handleDpopValidation(e: DpopValidationException): ResponseEntity<ErrorResponse> {
        log.info("DPoP rejected: {}", OrchestratorException.loggable(e.message.orEmpty()))
        return respond(ErrorCode.UNAUTHORIZED, Text("Die Anfrage konnte nicht authentifiziert werden ({detail}).", "detail" to e.failure.name))
    }

    /**
     * Missing/invalid Keycloak peer-auth assertion (docs/12-entscheidungen.md ADR-7): 401 with a
     * neutral text. Key ids and issuers are for the log, not for whoever sent the request.
     */
    @ExceptionHandler(PeerAuthValidationException::class)
    fun handlePeerAuthValidation(e: PeerAuthValidationException): ResponseEntity<ErrorResponse> {
        log.info("Peer-auth rejected: {}", OrchestratorException.loggable(e.message.orEmpty()))
        return respond(ErrorCode.UNAUTHORIZED, Text("Die Anfrage konnte nicht authentifiziert werden."))
    }

    /** The channel ended with its Keycloak session (ADR-43): 410, like an expired login on a token request. */
    @ExceptionHandler(ChannelSessionEndedException::class)
    fun handleChannelSessionEnded(e: ChannelSessionEndedException): ResponseEntity<ErrorResponse> {
        log.info("{}", e.message)
        return respond(ErrorCode.PROCESS_GONE, Text("Die Anmeldung ist abgelaufen. Bitte melden Sie sich neu an."))
    }

    /** The journey ended as FAILED (I-2): 410, like any aborted process. */
    @ExceptionHandler(JourneyEndedException::class)
    fun handleJourneyEnded(e: JourneyEndedException): ResponseEntity<ErrorResponse> {
        log.info("{}: {}", ErrorCode.PROCESS_ABORTED, e.message)
        return respond(ErrorCode.PROCESS_ABORTED, e.text)
    }

    @ExceptionHandler(OrchestratorException::class)
    fun handleOrchestratorException(ex: OrchestratorException): ResponseEntity<ErrorResponse> {
        // The response carries words only; which session, account or tool it was is for the log.
        log.info("{}: {}", ex.code, ex.message)
        return respond(ex.code, ex.text)
    }

    /**
     * A value the client sent was rejected: 400 (docs/07-betrieb.md #1). An [InvalidInputException]
     * carries the words for the user. Any other message stays out of the response and out of the
     * log: it may name internals or echo what the user typed (I-19). The log gets the type and where
     * it was thrown. Relies on the rule: `require` for rejected input, `check`/`error()` for bugs.
     */
    @ExceptionHandler(IllegalArgumentException::class)
    fun handleIllegalArgument(e: IllegalArgumentException): ResponseEntity<ErrorResponse> {
        if (e is InvalidInputException) return respond(ErrorCode.BAD_REQUEST, e.text)
        log.info("Rejected input: {} at {}", e.javaClass.simpleName, e.stackTrace.firstOrNull())
        return respond(ErrorCode.BAD_REQUEST, Text("Die Eingabe ist ungültig."))
    }

    /** The body is not valid JSON or does not fit the request type. Spring's own shape otherwise. */
    @ExceptionHandler(HttpMessageNotReadableException::class)
    fun handleUnreadableBody(e: HttpMessageNotReadableException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.BAD_REQUEST, Text("Die Anfrage ist nicht lesbar."))

    /** A path or query value of the wrong type, e.g. a channelSessionId that is not a UUID. */
    @ExceptionHandler(MethodArgumentTypeMismatchException::class)
    fun handleTypeMismatch(e: MethodArgumentTypeMismatchException): ResponseEntity<ErrorResponse> =
        respond(ErrorCode.BAD_REQUEST, Text("Ungültiger Wert für '{name}'.", "name" to e.name))

    /**
     * A broken internal assumption (`check`, `error()`): 500 with a neutral text, details in the log.
     * Not 409: real business conflicts are explicit via `OrchestratorException.invalidState`
     * (docs/07-betrieb.md #1).
     */
    @ExceptionHandler(IllegalStateException::class)
    fun handleIllegalState(e: IllegalStateException): ResponseEntity<ErrorResponse> {
        log.error("Internal error", e)
        return respond(ErrorCode.INTERNAL_ERROR, Text("Ein interner Fehler ist aufgetreten."))
    }

    @ExceptionHandler(IdentityConflictException::class)
    fun handleIdentityConflict(e: IdentityConflictException): ResponseEntity<ErrorResponse> {
        log.warn("Identity claim conflict: {}", e.message)
        return respond(ErrorCode.INVALID_STATE_TRANSITION, e.text)
    }

    // Nested in a flush or commit exception it is found by handleUnexpected.
    @ExceptionHandler(ConstraintViolationException::class)
    fun handleConstraintViolation(e: ConstraintViolationException): ResponseEntity<ErrorResponse> {
        // H2 reports schema-qualified names, optionally followed by " ON ..." (unique index) or
        // " INDEX <backing index> ON ..." (named unique constraint); never inspect values.
        val constraint = e.constraintName?.substringBefore(" ON ")?.substringBefore(" INDEX ")
            ?.substringAfterLast('.')?.trim('"')?.lowercase(Locale.ROOT)
        if (e.sqlState != "23505" || constraint !in ACCOUNT_BINDING_CONSTRAINTS) return internalError(e)
        log.warn("Concurrent account binding rejected by {}", constraint)
        return respond(ErrorCode.INVALID_STATE_TRANSITION, Text("Diese Identität gehört bereits zu einem anderen Konto."))
    }

    /** Fachlich unverarbeitbar, kein Nutzereingabefehler (unknown enrollmentRef) - docs/07-betrieb.md #1: 422. */
    @ExceptionHandler(UnresolvableReferenceException::class)
    fun handleUnresolvableReference(e: UnresolvableReferenceException): ResponseEntity<ErrorResponse> {
        log.info("{}: {}", ErrorCode.UNRESOLVABLE_REFERENCE, e.message)
        return respond(ErrorCode.UNRESOLVABLE_REFERENCE, e.text)
    }

    /** A tool module refused what does not fit the account's state: 409, like `invalidState`. */
    @ExceptionHandler(InvalidStateException::class)
    fun handleInvalidState(e: InvalidStateException): ResponseEntity<ErrorResponse> {
        log.info("{}: {}", ErrorCode.INVALID_STATE_TRANSITION, e.message)
        return respond(ErrorCode.INVALID_STATE_TRANSITION, e.text)
    }

    /** A tool module's own budget refused a repeat (ADR-44): 429, like the orchestrator's own limits. */
    @ExceptionHandler(TooManyRequestsException::class)
    fun handleTooManyRequests(e: TooManyRequestsException): ResponseEntity<ErrorResponse> {
        log.info("{}: {}", ErrorCode.TOO_MANY_REQUESTS, e.message)
        return respond(ErrorCode.TOO_MANY_REQUESTS, e.text)
    }

    /**
     * Two requests raced on the same `@Version` row: 409 (docs/07-betrieb.md #1). The loser retries
     * against freshly read state.
     */
    @ExceptionHandler(ObjectOptimisticLockingFailureException::class)
    fun handleConcurrentModification(e: ObjectOptimisticLockingFailureException): ResponseEntity<ErrorResponse> {
        // The response is opaque. The log names the entity, which tells a real race from a
        // self-inflicted one.
        log.warn("Optimistic lock conflict on {} id={}", e.persistentClassName, e.identifier, e)
        return respond(ErrorCode.CONCURRENT_MODIFICATION, Text("Gleichzeitige Anfrage in derselben Sitzung - bitte erneut versuchen."))
    }

    /**
     * Everything else gets 500 with a fixed text, so every answer is an `ErrorResponse`
     * (docs/07-betrieb.md #1). Spring's own web exceptions (404, 405, 415) keep their status.
     * Since this handler always matches, wrapped exceptions with a rule of their own (a binding
     * conflict inside a flush or commit) are looked for in the cause chain here.
     */
    @ExceptionHandler(Exception::class)
    fun handleUnexpected(e: Exception): ResponseEntity<ErrorResponse> {
        if (e is org.springframework.web.ErrorResponse) throw e
        return when (val known = generateSequence(e.cause) { it.cause }.firstOrNull { it is ConstraintViolationException || it is ObjectOptimisticLockingFailureException }) {
            is ConstraintViolationException -> handleConstraintViolation(known)
            is ObjectOptimisticLockingFailureException -> handleConcurrentModification(known)
            else -> internalError(e)
        }
    }

    private fun internalError(e: Exception): ResponseEntity<ErrorResponse> {
        log.error("Unexpected error", e)
        return respond(ErrorCode.INTERNAL_ERROR, Text("Ein interner Fehler ist aufgetreten."))
    }

    private fun respond(code: ErrorCode, text: Text): ResponseEntity<ErrorResponse> =
        ResponseEntity.status(code.httpStatus).body(ErrorResponse(code, text))

    private companion object {
        private val ACCOUNT_BINDING_CONSTRAINTS = setOf(
            "ux_anchor_value", "ux_anchor_account_type"
        )
        private val log = LoggerFactory.getLogger(OrchestratorExceptionHandler::class.java)
    }
}
