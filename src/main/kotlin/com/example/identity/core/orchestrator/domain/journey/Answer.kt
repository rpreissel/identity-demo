package com.example.identity.core.orchestrator.domain.journey

/**
 * The answer to the yes/no question of an [com.example.identity.core.orchestrator.domain.journey.state.AnswerableState]
 * ([com.example.identity.core.orchestrator.domain.journey.state.Question.Confirm]). Parsed once where the
 * client's answer comes in, so a journey only ever sees one of these two.
 */
enum class Answer(val wire: String) {
    ACCEPT("accept"),
    DECLINE("decline");

    companion object {
        /** The answer the client sent as [wire], or null when it is neither. */
        fun fromWire(value: String): Answer? = entries.firstOrNull { it.wire == value }
    }
}
