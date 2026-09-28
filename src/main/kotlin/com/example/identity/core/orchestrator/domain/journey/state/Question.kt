package com.example.identity.core.orchestrator.domain.journey.state

import com.example.identity.contract.texts.Text

/**
 * The question an [AnswerableState] asks while it waits, authored by the backend. The app has
 * week-long release cycles, so screen text must change without an app release; it travels as
 * `stepData.prompt`, while `next.context`/`next.step` stay pure addresses. The domain form: on the
 * wire it is `Prompt`, mapped in `OrchestratorStepData.kt` (docs/adr/ADR-040-fachkern-im-paket-domain.md).
 */
sealed interface Question {
    val title: Text
    val description: Text?

    /** Answered via the existing generic `answer` endpoint with `"accept"` or `"decline"`. */
    data class Confirm(
        override val title: Text,
        override val description: Text?,
        val confirmLabel: Text,
        val cancelLabel: Text,
        /** Signals the client to render the confirming action as a destructive/dangerous one. */
        val destructive: Boolean = false
    ) : Question
}
