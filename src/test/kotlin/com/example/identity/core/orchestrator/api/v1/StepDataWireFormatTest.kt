package com.example.identity.core.orchestrator.api.v1

import com.example.identity.contract.tool_api.MissingFields
import com.example.identity.contract.tool_api.StepDataTypes
import com.example.identity.contract.tool_api.stepData
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures
import com.example.identity.core.orchestrator.journey.MessageStep
import com.example.identity.core.orchestrator.journey.OrchestratorStepDataTypes
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.string.shouldContain

/** The `kind` of every StepData shape is named where the shape is declared, once and unambiguously. */
class StepDataWireFormatTest : BehaviorSpec({

    given("the declarations of the application") {
        val kinds = StepDataWireFormat.declaredKinds(
            StrategyTestFixtures.modules,
            listOf(OrchestratorStepDataTypes().orchestratorOwnStepDataTypes(), SharedStepDataTypes().sharedStepDataShapes()),
        )

        then("every shape has the kind the v1 contract publishes") {
            kinds.keys shouldContainExactlyInAnyOrder listOf(
                "missing-fields", "select-method", "message", "confirm", "failed-attempt",
                "nect-redirect", "qr-pairing", "kobil-unlock", "kobil-otp", "kobil-activation",
                "enroll-sms", "enroll-password",
            )
        }
    }

    given("two declarations that clash") {
        then("a kind given twice stops the start") {
            val twice = listOf(
                StepDataTypes { mapOf("missing-fields" to stepData<MissingFields>("a")) },
                StepDataTypes { mapOf("missing-fields" to stepData<MessageStep>("b")) },
            )
            shouldThrow<IllegalStateException> { StepDataWireFormat.declaredKinds(emptyList(), twice) }
                .message shouldContain "more than once"
        }
        then("one shape under two kinds stops the start") {
            val renamed = listOf(StepDataTypes { mapOf("a" to stepData<MissingFields>("a"), "b" to stepData<MissingFields>("b")) })
            shouldThrow<IllegalStateException> { StepDataWireFormat.declaredKinds(emptyList(), renamed) }
                .message shouldContain "several kinds"
        }
    }
})
