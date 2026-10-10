package com.example.identity.core.orchestrator.domain

import com.example.identity.contract.texts.Text

/** Error contract from docs/07-betrieb.md #1: HTTP errors are reserved for disrupted flows. */
class OrchestratorException(
    val code: ErrorCode,
    /** What the caller is told, as a text reference (docs/adr/ADR-033). */
    val text: Text,
    /** What only the log gets - ids and the like, useless to a reader and not theirs to see. */
    detail: String? = null
) : RuntimeException(detail?.let { "${text.template} (${loggable(it)})" } ?: text.template) {

    companion object {
        const val MAX_DETAIL_LENGTH = 200
        private val LINE_BREAKING = Regex("[\\p{Cc}\\p{Zl}\\p{Zp}]")

        /**
         * A detail may carry what a client sent (a path segment, a query value). Without control
         * characters and bounded, it cannot forge a log line or flood the log.
         */
        fun loggable(detail: String): String =
            detail.replace(LINE_BREAKING, "?").let { if (it.length > MAX_DETAIL_LENGTH) it.take(MAX_DETAIL_LENGTH) + "..." else it }

        fun notFound(text: Text, detail: String? = null) =
            OrchestratorException(ErrorCode.NOT_FOUND, text, detail)

        fun bindingMismatch(text: Text, detail: String? = null) =
            OrchestratorException(ErrorCode.BINDING_MISMATCH, text, detail)

        fun invalidState(text: Text, detail: String? = null) =
            OrchestratorException(ErrorCode.INVALID_STATE_TRANSITION, text, detail)

        /** Process expired/consumed, or aborted after exhausted retries. */
        fun processGone(text: Text, detail: String? = null) =
            OrchestratorException(ErrorCode.PROCESS_GONE, text, detail)

        /**
         * Account-level brute-force rate limit tripped (AccountLockoutService). Only raised where the
         * caller already knows the account. A lookup-based tool folds its lock into its ordinary
         * failure instead; otherwise this response reveals whether an account exists.
         */
        fun accountLocked(text: Text, detail: String? = null) =
            OrchestratorException(ErrorCode.ACCOUNT_LOCKED, text, detail)

        /** Rate limit on an unauthenticated, cheap-to-repeat operation (ChannelCreationRateLimitService). */
        fun tooManyRequests(text: Text, detail: String? = null) =
            OrchestratorException(ErrorCode.TOO_MANY_REQUESTS, text, detail)
    }
}
