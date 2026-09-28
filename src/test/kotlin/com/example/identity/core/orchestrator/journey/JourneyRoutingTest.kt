package com.example.identity.core.orchestrator.journey

import com.example.identity.tools.auth_email.EnrollEmailDescriptor
import com.example.identity.tools.auth_sms.EnrollSmsDescriptor
import com.example.identity.core.orchestrator.domain.journey.state.ManageAuthMethodsState
import com.example.identity.core.orchestrator.domain.journey.state.Offer
import com.example.identity.core.orchestrator.domain.ChannelType
import com.example.identity.core.orchestrator.session.ChannelSession
import com.example.identity.core.orchestrator.tool.ToolAvailabilityService
import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import com.example.identity.contract.texts.Text
import com.example.identity.contract.tool_api.envelope.Next
import com.example.identity.contract.tool_api.ToolId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk

/**
 * A single candidate is started on its own, unless activating it completes it at once
 * (ToolDescriptor.completesOnActivation). Otherwise a method would be added that the user never
 * saw or chose.
 */
class JourneyRoutingTest : BehaviorSpec({

    val availability = mockk<ToolAvailabilityService> {
        every { disabledToolIds(any()) } returns emptySet()
        every { ordered(any(), any()) } answers { secondArg<Collection<ToolId>>().toList() }
    }
    val routing = JourneyRouting(ToolHandlerRegistry(listOf(EnrollEmailDescriptor, EnrollSmsDescriptor)), availability)
    val webChannel = ChannelSession(channel = ChannelType.KEYCLOAK).apply {
        availableClientTools = mutableSetOf("enroll-email", "enroll-sms")
    }

    fun adding(vararg tools: String) = ManageAuthMethodsState.Enrolling(Offer(tools.map { ToolId(it) }))

    given("adding a sign-in method with only enroll-email left") {
        val step = routing.stepFor(adding("enroll-email"), webChannel)

        then("the selection page opens instead of starting it, naming why there is just this one") {
            step.next shouldBe Next.orchestrator("enrollment", "selectMethod")
            val select = step.stepData.shouldBeInstanceOf<SelectMethodStep>()
            select.options shouldBe listOf("enroll-email")
            select.title shouldBe Text("Neues Anmeldeverfahren hinzufügen")
            select.description shouldBe Text(
                "Nur dieses Verfahren steht hier noch zur Wahl. {grund}",
                "grund" to EnrollEmailDescriptor.completesOnActivation,
            )
        }
    }

    given("adding a sign-in method with only enroll-sms left") {
        then("it still starts on its own - it has a step of its own to show") {
            routing.stepFor(adding("enroll-sms"), webChannel).next shouldBe Next.tool("enroll-sms", EnrollSmsDescriptor.startStep)
        }
    }

    given("adding a sign-in method with both left") {
        then("the ordinary selection, with the state's own description") {
            val select = routing.stepFor(adding("enroll-email", "enroll-sms"), webChannel).stepData.shouldBeInstanceOf<SelectMethodStep>()
            select.options shouldBe listOf("enroll-email", "enroll-sms")
            select.description shouldBe adding().selectionDescription
        }
    }
})
