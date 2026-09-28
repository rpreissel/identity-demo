package com.example.identity.core.orchestrator.journey

import com.example.identity.contract.texts.Text
import com.example.identity.core.orchestrator.domain.journey.state.Question
import com.example.identity.contract.tool_api.StepData
import com.example.identity.contract.tool_api.StepDataTypes
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * The step shapes the orchestrator produces for its own screens. Three come from
 * [JourneyRouting.stepFor], one from `JourneyService`'s failed-attempt branch. Every other
 * `stepData` on the wire belongs to a tool.
 */

/** Several candidates are open, so the client shows a choice (docs/04-orchestrierung.md #4). */
@JsonTypeName("select-method")
// NON_NULL like the other envelope DTOs: an absent description is an absent key on the wire.
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "Several procedures are possible; the client shows a selection.")
data class SelectMethodStep(
    @field:Schema(example = "[\"auth-password\", \"auth-device\"]")
    val options: List<String>,
    val title: Text?,
    val description: Text? = null
) : StepData

/**
 * Exactly one candidate was open, so the selection screen is skipped. The description still has
 * to reach the client, because it explains why this step is required.
 */
@JsonTypeName("message")
@Schema(description = "A single candidate was auto-activated; this explains why the step appears.")
data class MessageStep(
    val message: Text
) : StepData

/** An `AnswerableState` waits for `POST .../answer`; the text is authored by the backend. */
@JsonTypeName("confirm")
@Schema(description = "The step waits for a yes/no answer; the prompt is authored by the backend.")
data class ConfirmStep(val prompt: Prompt) : StepData

/**
 * A [Question] on the wire, discriminated by `kind` like every polymorphic wire type (see
 * `tool_api.StepData`). Kept apart from the domain [Question] so the states carry no
 * serialization (ADR-40).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "kind")
@JsonSubTypes(
    JsonSubTypes.Type(value = Prompt.Confirm::class, name = "Confirm")
)
sealed interface Prompt {
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
    ) : Prompt
}

fun Question.toPrompt(): Prompt = when (this) {
    is Question.Confirm -> Prompt.Confirm(title, description, confirmLabel, cancelLabel, destructive)
}

/**
 * An attempt failed and the journey stays where it is. Carries only the reason - what the step
 * still needs is answered by the tool's own GET, which reports its shape unchanged.
 */
@JsonTypeName("failed-attempt")
@Schema(description = "The attempt failed; retries remain.")
data class FailedAttemptStep(
    val error: Text
) : StepData

/** See [StepDataTypes] - this is the orchestrator's own declaration, next to the shapes. */
@Configuration
class OrchestratorStepDataTypes {

    @Bean
    fun orchestratorOwnStepDataTypes() = StepDataTypes {
        listOf(
            SelectMethodStep::class,
            MessageStep::class,
            ConfirmStep::class,
            FailedAttemptStep::class
        )
    }
}
