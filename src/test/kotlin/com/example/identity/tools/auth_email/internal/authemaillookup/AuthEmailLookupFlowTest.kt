package com.example.identity.tools.auth_email.internal.authemaillookup
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.TEST_CLOCK
import com.example.identity.tools.auth_email.internal.EmailCodeGenerator

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/** Pure unit test for the code-vs-state decision - mirrors `auth_sms`'s `AuthSmsLookupFlowTest`. */
class AuthEmailLookupFlowTest : BehaviorSpec({

    val emailCodeGenerator = EmailCodeGenerator("test-pepper", clock = TEST_CLOCK)

    given("a session still awaiting the email") {
        val state = AuthEmailLookupState.AwaitingEmail

        `when`("a code is submitted before any email was ever resolved") {
            val decision = AuthEmailLookupFlow.decideCode(state, "000000", emailCodeGenerator)

            then("the state is unchanged - not a wrong-code failure") {
                decision shouldBe AuthEmailLookupDecision.Unchanged(state)
            }
        }
    }

    given("a pending code for a resolved account") {
        val issued = emailCodeGenerator.issue()
        val state = AuthEmailLookupState.AwaitingCode(accountId = AccountId(42L), issued.hash, issued.expiresAt)

        `when`("nothing was submitted") {
            val decision = AuthEmailLookupFlow.decideCode(state, null, emailCodeGenerator)

            then("the state is unchanged") {
                decision shouldBe AuthEmailLookupDecision.Unchanged(state)
            }
        }

        `when`("the wrong code was submitted") {
            val decision = AuthEmailLookupFlow.decideCode(state, "000000", emailCodeGenerator)

            then("it is rejected, naming the account for the throttle") {
                decision shouldBe AuthEmailLookupDecision.WrongCode(AccountId(42L))
            }
        }

        `when`("the correct code was submitted") {
            val decision = AuthEmailLookupFlow.decideCode(state, issued.plain, emailCodeGenerator)

            then("it completes for that account") {
                decision shouldBe AuthEmailLookupDecision.Complete(AccountId(42L))
            }
        }
    }

    given("a pending code for an unresolved email (enumeration protection)") {
        val issued = emailCodeGenerator.issue()
        val state = AuthEmailLookupState.AwaitingCode(accountId = null, issued.hash, issued.expiresAt)

        `when`("the code that would have matched a real account is submitted") {
            val decision = AuthEmailLookupFlow.decideCode(state, issued.plain, emailCodeGenerator)

            then("it still fails - there is no account to complete for") {
                decision shouldBe AuthEmailLookupDecision.WrongCode(null)
            }
        }
    }

    given("persisted columns without an issued code") {
        `when`("the state is rebuilt from them") {
            val rebuilt = AuthEmailLookupState.of(ToolSessionId(UUID.randomUUID()), null, null, null)

            then("it is AwaitingEmail") {
                rebuilt shouldBe AuthEmailLookupState.AwaitingEmail
            }
        }
    }

    given("persisted columns with a code issued for a resolved account") {
        val issued = emailCodeGenerator.issue()

        `when`("the state is rebuilt from them") {
            val rebuilt = AuthEmailLookupState.of(ToolSessionId(UUID.randomUUID()), AccountId(42L), issued.hash, issued.expiresAt)

            then("it is AwaitingCode for that account") {
                rebuilt shouldBe AuthEmailLookupState.AwaitingCode(AccountId(42L), issued.hash, issued.expiresAt)
            }
        }
    }
})
