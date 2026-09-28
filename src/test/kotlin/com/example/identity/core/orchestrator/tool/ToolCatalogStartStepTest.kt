package com.example.identity.core.orchestrator.tool

import com.example.identity.contract.tool_api.ToolDescriptor
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.maps.shouldContainExactly
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

/**
 * Pins the start step of every registered tool against the real Spring-collected catalog.
 * [ToolDescriptor.startStep] derives it from `role`; a tool missing here fails ("catalog has tools not
 * covered") instead of defaulting to a plausible but wrong step.
 *
 * Keep in sync with the handlers' first `InProgress(nextStep = ...)`: both are the same contract.
 */
@SpringBootTest
@ActiveProfiles("test")
class ToolCatalogStartStepTest(toolRegistry: ToolHandlerRegistry) : BehaviorSpec({

    val expectedStartSteps = mapOf(
        "ident-fsc" to "input",
        "ident-eid" to "card",
        "ident-nect" to "redirect",
        "ident-kvnr" to "input",
        "enroll-sms" to "enroll",
        "auth-sms" to "auth",
        "auth-sms-lookup" to "auth",
        "enroll-password" to "enroll",
        "auth-password" to "auth",
        "auth-password-lookup" to "auth",
        "confirm-email" to "input",
        "enroll-email" to "enroll",
        "auth-email" to "auth",
        "auth-email-lookup" to "auth",
        "enroll-device" to "enroll",
        "auth-device" to "auth",
        "enroll-kobil" to "activate",
        // Not the AUTH role default "auth": the client first unlocks locally so the PIN can be
        // released, and only the step after that carries the proof.
        "auth-kobil" to "unlock",
        "enroll-qr" to "enroll",
        "auth-qr" to "waitForApp",
        "auth-qr-lookup" to "waitForApp",
        "confirm-qr-login" to "input"
    )

    given("the real Spring-collected tool catalog") {
        then("every registered tool starts on its documented step") {
            val actual = toolRegistry.descriptors().associate { it.toolId.value to it.startStep }
            actual shouldContainExactly expectedStartSteps
        }
    }
})
