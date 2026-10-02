package com.example.identity.tools.auth_sms.internal.authsmslookup
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.TEST_CLOCK
import com.example.identity.tools.auth_sms.internal.TanGenerator

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Pure unit test for the tan-vs-state decision. [AuthSmsLookupToolHandlerTest] covers the
 * persistence and the enumeration-neutral answers.
 */
class AuthSmsLookupFlowTest : BehaviorSpec({

    val tanGenerator = TanGenerator("test-pepper", clock = TEST_CLOCK)

    given("a session still awaiting the email") {
        val state = AuthSmsLookupState.AwaitingEmail

        `when`("a tan is submitted before any email was ever resolved") {
            val decision = AuthSmsLookupFlow.decideTan(state, "000000", tanGenerator)

            then("the state is unchanged - not a wrong-tan failure") {
                decision shouldBe AuthSmsLookupDecision.Unchanged(state)
            }
        }
    }

    given("a pending TAN for a resolved account") {
        val issued = tanGenerator.issue()
        val state = AuthSmsLookupState.AwaitingTan(accountId = AccountId(42L), issued.hash, issued.expiresAt)

        `when`("nothing was submitted") {
            val decision = AuthSmsLookupFlow.decideTan(state, null, tanGenerator)

            then("the state is unchanged") {
                decision shouldBe AuthSmsLookupDecision.Unchanged(state)
            }
        }

        `when`("the wrong tan was submitted") {
            val decision = AuthSmsLookupFlow.decideTan(state, "000000", tanGenerator)

            then("it is rejected, naming the account for the throttle") {
                decision shouldBe AuthSmsLookupDecision.WrongTan(AccountId(42L))
            }
        }

        `when`("the correct tan was submitted") {
            val decision = AuthSmsLookupFlow.decideTan(state, issued.plainTan, tanGenerator)

            then("it completes for that account") {
                decision shouldBe AuthSmsLookupDecision.Complete(AccountId(42L))
            }
        }
    }

    given("a pending TAN for an unresolved email (enumeration protection)") {
        val issued = tanGenerator.issue()
        val state = AuthSmsLookupState.AwaitingTan(accountId = null, issued.hash, issued.expiresAt)

        `when`("the tan that would have matched a real account is submitted") {
            val decision = AuthSmsLookupFlow.decideTan(state, issued.plainTan, tanGenerator)

            then("it still fails - there is no account to complete for") {
                decision shouldBe AuthSmsLookupDecision.WrongTan(null)
            }
        }
    }

    given("persisted columns without an issued tan") {
        `when`("the state is rebuilt from them") {
            val rebuilt = AuthSmsLookupState.of(ToolSessionId(UUID.randomUUID()), null, null, null)

            then("it is AwaitingEmail") {
                rebuilt shouldBe AuthSmsLookupState.AwaitingEmail
            }
        }
    }

    given("persisted columns with a tan issued for a resolved account") {
        val issued = tanGenerator.issue()

        `when`("the state is rebuilt from them") {
            val rebuilt = AuthSmsLookupState.of(ToolSessionId(UUID.randomUUID()), AccountId(42L), issued.hash, issued.expiresAt)

            then("it is AwaitingTan for that account") {
                rebuilt shouldBe AuthSmsLookupState.AwaitingTan(AccountId(42L), issued.hash, issued.expiresAt)
            }
        }
    }
})
