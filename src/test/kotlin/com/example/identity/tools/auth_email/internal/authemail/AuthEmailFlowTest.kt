package com.example.identity.tools.auth_email.internal.authemail
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.TEST_CLOCK
import com.example.identity.tools.auth_email.internal.EmailCodeGenerator

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class AuthEmailFlowTest : BehaviorSpec({

    val emailCodeGenerator = EmailCodeGenerator("test-pepper", clock = TEST_CLOCK)
    val issued = emailCodeGenerator.issue()
    val state = AuthEmailState(issued.hash, issued.expiresAt)

    given("a pending code") {
        `when`("nothing was submitted") {
            val decision = AuthEmailFlow.decide(state, AuthEmailInput(), emailCodeGenerator)

            then("the state is unchanged") {
                decision shouldBe AuthEmailDecision.Unchanged
            }
        }

        `when`("the wrong code was submitted") {
            val decision = AuthEmailFlow.decide(state, AuthEmailInput("000000"), emailCodeGenerator)

            then("it is rejected") {
                decision shouldBe AuthEmailDecision.WrongCode
            }
        }

        `when`("the correct code was submitted") {
            val decision = AuthEmailFlow.decide(state, AuthEmailInput(issued.plain), emailCodeGenerator)

            then("it completes") {
                decision shouldBe AuthEmailDecision.Complete
            }
        }
    }

    given("the persisted hash and expiry of a pending code") {
        `when`("the state is rebuilt from them") {
            val rebuilt = AuthEmailState.of(ToolSessionId(UUID.randomUUID()), issued.hash, issued.expiresAt)

            then("it is the pending code's state") {
                rebuilt shouldBe state
            }
        }
    }
})
