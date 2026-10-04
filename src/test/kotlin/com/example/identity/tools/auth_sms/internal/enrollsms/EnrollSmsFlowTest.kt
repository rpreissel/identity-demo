package com.example.identity.tools.auth_sms.internal.enrollsms
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.TEST_CLOCK
import com.example.identity.tools.auth_sms.internal.TanGenerator
import com.example.identity.tools.auth_sms.api.v1.EnrollSmsStep

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Pure unit test for the decision table alone: no Spring, no repositories, no [EnrollSmsToolHandler] -
 * just [EnrollSmsState] in, [EnrollSmsDecision] out. [EnrollSmsToolHandlerTest] covers the other half,
 * how a [EnrollSmsDecision] gets translated into persistence and [com.example.identity.contract.tool_api.ToolOutcome].
 * Which numbers count as valid is [com.example.identity.contract.tool_api.values.PhoneNumberTest]'s matter.
 */
class EnrollSmsFlowTest : BehaviorSpec({

    // Explicit pepper so issue()/matches() stay reproducible within the test run.
    val tanGenerator = TanGenerator("test-pepper", clock = TEST_CLOCK)

    given("a session still awaiting the phone number") {
        val state = EnrollSmsState.AwaitingPhoneNumber

        `when`("nothing was submitted") {
            val decision = EnrollSmsFlow.decide(state, EnrollSmsInput(), tanGenerator)

            then("the state is unchanged") {
                decision shouldBe EnrollSmsDecision.Unchanged(state)
            }
        }

        `when`("an unrecognizable phone number was submitted") {
            val decision = EnrollSmsFlow.decide(state, EnrollSmsInput(phoneNumber = "not-a-number"), tanGenerator)

            then("it is rejected as invalid") {
                decision shouldBe EnrollSmsDecision.InvalidPhoneNumber("not-a-number")
            }
        }

        `when`("a valid phone number was submitted") {
            val decision = EnrollSmsFlow.decide(state, EnrollSmsInput(phoneNumber = "+49 170 1234567"), tanGenerator)

            then("a TAN is sent to the normalized number") {
                decision shouldBe EnrollSmsDecision.SendTan("+491701234567")
            }
        }

        `when`("a valid phone number AND a tan were submitted together") {
            val decision = EnrollSmsFlow.decide(state, EnrollSmsInput(phoneNumber = "+49 170 1234567", tan = "123456"), tanGenerator)

            then("the phone number wins - there is no pending TAN yet for any tan to be checked against") {
                decision shouldBe EnrollSmsDecision.SendTan("+491701234567")
            }
        }
    }

    given("a session awaiting the phone number, in version 2 without consent yet (ADR-51)") {
        val state = EnrollSmsState.AwaitingPhoneNumber

        `when`("a valid phone number comes without the consent") {
            val decision = EnrollSmsFlow.decide(state, EnrollSmsInput(phoneNumber = "+49 170 1234567"), tanGenerator, needsConsent = true)

            then("nothing is sent: the consent is missing") {
                decision shouldBe EnrollSmsDecision.ConsentMissing(state)
            }
        }

        `when`("a valid phone number comes with the consent") {
            val decision = EnrollSmsFlow.decide(state, EnrollSmsInput(phoneNumber = "+49 170 1234567", consent = true), tanGenerator, needsConsent = true)

            then("a TAN is sent") {
                decision shouldBe EnrollSmsDecision.SendTan("+491701234567")
            }
        }

        `when`("the step is described") {
            val (_, stepData) = state.describe(replaces = false, needsConsent = true)

            then("it names the consent beside the number") {
                stepData shouldBe EnrollSmsStep(listOf("phoneNumber", "consent"), replaces = false)
            }
        }
    }

    given("a pending TAN for +491701234567") {
        val issued = tanGenerator.issue()
        val state = EnrollSmsState.AwaitingTan("+491701234567", issued.hash, issued.expiresAt)

        `when`("nothing was submitted") {
            val decision = EnrollSmsFlow.decide(state, EnrollSmsInput(), tanGenerator)

            then("the state is unchanged") {
                decision shouldBe EnrollSmsDecision.Unchanged(state)
            }
        }

        `when`("the wrong tan was submitted") {
            val decision = EnrollSmsFlow.decide(state, EnrollSmsInput(tan = "000000"), tanGenerator)

            then("it is rejected without completing, carrying the unchanged state") {
                decision shouldBe EnrollSmsDecision.WrongTan(state)
            }
        }

        `when`("the correct tan was submitted") {
            val decision = EnrollSmsFlow.decide(state, EnrollSmsInput(tan = issued.plainTan), tanGenerator)

            then("the enrollment completes for this phone number") {
                decision shouldBe EnrollSmsDecision.Complete("+491701234567")
            }
        }

        `when`("a different phone number AND the still-valid tan for the old one were submitted together") {
            val decision = EnrollSmsFlow.decide(state, EnrollSmsInput(phoneNumber = "+49 171 9999999", tan = issued.plainTan), tanGenerator)

            then("the new number wins - a changed number invalidates whatever TAN was pending for the old one") {
                decision shouldBe EnrollSmsDecision.SendTan("+491719999999")
            }
        }

        `when`("an unrecognizable phone number was submitted instead") {
            val decision = EnrollSmsFlow.decide(state, EnrollSmsInput(phoneNumber = "not-a-number"), tanGenerator)

            then("it is rejected as invalid, the pending TAN untouched") {
                decision shouldBe EnrollSmsDecision.InvalidPhoneNumber("not-a-number")
            }
        }
    }

    given("persisted columns without a phone number") {
        `when`("the state is rebuilt from them") {
            val rebuilt = EnrollSmsState.of(ToolSessionId(UUID.randomUUID()), null, null, null)

            then("it is AwaitingPhoneNumber") {
                rebuilt shouldBe EnrollSmsState.AwaitingPhoneNumber
            }
        }
    }

    given("persisted columns with a phone number and its tan data") {
        val issued = tanGenerator.issue()

        `when`("the state is rebuilt from them") {
            val rebuilt = EnrollSmsState.of(ToolSessionId(UUID.randomUUID()), "+491701234567", issued.hash, issued.expiresAt)

            then("it is AwaitingTan for that number") {
                rebuilt shouldBe EnrollSmsState.AwaitingTan("+491701234567", issued.hash, issued.expiresAt)
            }
        }
    }
})
