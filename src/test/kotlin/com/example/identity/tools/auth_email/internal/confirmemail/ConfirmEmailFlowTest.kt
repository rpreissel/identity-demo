package com.example.identity.tools.auth_email.internal.confirmemail
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.TEST_CLOCK
import com.example.identity.tools.auth_email.internal.EmailCodeGenerator

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Pure unit test for the decision table alone - mirrors `auth_sms`'s `EnrollSmsFlowTest`.
 * [ConfirmEmailToolHandlerTest] covers the other half: how a [ConfirmEmailDecision] gets
 * translated into persistence, `account` writes and [com.example.identity.contract.tool_api.ToolOutcome].
 */
class ConfirmEmailFlowTest : BehaviorSpec({

    val emailCodeGenerator = EmailCodeGenerator("test-pepper", clock = TEST_CLOCK)

    given("a session still awaiting the email") {
        val state = ConfirmEmailState.AwaitingEmail

        `when`("nothing was submitted") {
            val decision = ConfirmEmailFlow.decide(state, ConfirmEmailInput(), emailCodeGenerator)

            then("the state is unchanged") {
                decision shouldBe ConfirmEmailDecision.Unchanged(state)
            }
        }

        `when`("an unrecognizable email was submitted") {
            val decision = ConfirmEmailFlow.decide(state, ConfirmEmailInput(email = "not-an-email"), emailCodeGenerator)

            then("it is rejected as invalid") {
                decision shouldBe ConfirmEmailDecision.InvalidEmail("not-an-email")
            }
        }

        `when`("a valid email was submitted") {
            val decision = ConfirmEmailFlow.decide(state, ConfirmEmailInput(email = " Max@Example.com "), emailCodeGenerator)

            then("a code is requested for the normalized address") {
                decision shouldBe ConfirmEmailDecision.RequestCode("max@example.com")
            }
        }

        `when`("a valid email AND a code were submitted together") {
            val decision = ConfirmEmailFlow.decide(state, ConfirmEmailInput(email = "max@example.com", code = "123456"), emailCodeGenerator)

            then("the email wins - there is no pending code yet for any code to be checked against") {
                decision shouldBe ConfirmEmailDecision.RequestCode("max@example.com")
            }
        }
    }

    given("a pending code for max@example.com") {
        val issued = emailCodeGenerator.issue()
        val state = ConfirmEmailState.AwaitingCode("max@example.com", issued.hash, issued.expiresAt)

        `when`("nothing was submitted") {
            val decision = ConfirmEmailFlow.decide(state, ConfirmEmailInput(), emailCodeGenerator)

            then("the state is unchanged") {
                decision shouldBe ConfirmEmailDecision.Unchanged(state)
            }
        }

        `when`("the wrong code was submitted") {
            val decision = ConfirmEmailFlow.decide(state, ConfirmEmailInput(code = "000000"), emailCodeGenerator)

            then("it is rejected without completing") {
                decision shouldBe ConfirmEmailDecision.WrongCode(state)
            }
        }

        `when`("the correct code was submitted") {
            val decision = ConfirmEmailFlow.decide(state, ConfirmEmailInput(code = issued.plain), emailCodeGenerator)

            then("the enrollment completes for this email") {
                decision shouldBe ConfirmEmailDecision.Complete("max@example.com")
            }
        }

        `when`("a different email AND the still-valid code for the old one were submitted together") {
            val decision = ConfirmEmailFlow.decide(state, ConfirmEmailInput(email = "other@example.com", code = issued.plain), emailCodeGenerator)

            then("the new email wins - a changed address invalidates whatever code was pending for the old one") {
                decision shouldBe ConfirmEmailDecision.RequestCode("other@example.com")
            }
        }
    }

    given("persisted columns without an email") {
        `when`("the state is rebuilt from them") {
            val rebuilt = ConfirmEmailState.of(ToolSessionId(UUID.randomUUID()), null, null, null)

            then("it is AwaitingEmail") {
                rebuilt shouldBe ConfirmEmailState.AwaitingEmail
            }
        }
    }

    given("persisted columns with an email and its code data") {
        val issued = emailCodeGenerator.issue()

        `when`("the state is rebuilt from them") {
            val rebuilt = ConfirmEmailState.of(ToolSessionId(UUID.randomUUID()), "max@example.com", issued.hash, issued.expiresAt)

            then("it is AwaitingCode for that email") {
                rebuilt shouldBe ConfirmEmailState.AwaitingCode("max@example.com", issued.hash, issued.expiresAt)
            }
        }
    }
})
