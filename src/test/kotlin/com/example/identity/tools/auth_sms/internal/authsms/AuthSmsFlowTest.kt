package com.example.identity.tools.auth_sms.internal.authsms

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.TEST_CLOCK
import com.example.identity.tools.auth_sms.internal.TanGenerator
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

class AuthSmsFlowTest : BehaviorSpec({

    val tanGenerator = TanGenerator("test-pepper", clock = TEST_CLOCK)
    val issued = tanGenerator.issue()
    val state = AuthSmsState(issued.hash, issued.expiresAt)

    given("a pending TAN") {
        `when`("nothing was submitted") {
            val decision = AuthSmsFlow.decide(state, AuthSmsInput(), tanGenerator)

            then("the state is unchanged") {
                decision shouldBe AuthSmsDecision.Unchanged
            }
        }

        `when`("the wrong tan was submitted") {
            val decision = AuthSmsFlow.decide(state, AuthSmsInput("000000"), tanGenerator)

            then("it is rejected") {
                decision shouldBe AuthSmsDecision.WrongTan
            }
        }

        `when`("the correct tan was submitted") {
            val decision = AuthSmsFlow.decide(state, AuthSmsInput(issued.plain), tanGenerator)

            then("it completes") {
                decision shouldBe AuthSmsDecision.Complete
            }
        }
    }

    given("the persisted hash and expiry of a pending TAN") {
        `when`("the state is rebuilt from them") {
            val rebuilt = AuthSmsState.of(ToolSessionId(UUID.randomUUID()), issued.hash, issued.expiresAt)

            then("it is the pending TAN's state") {
                rebuilt shouldBe state
            }
        }
    }
})
