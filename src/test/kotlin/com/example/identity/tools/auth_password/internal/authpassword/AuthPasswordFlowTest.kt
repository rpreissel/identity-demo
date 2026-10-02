package com.example.identity.tools.auth_password.internal.authpassword

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class AuthPasswordFlowTest : BehaviorSpec({

    given("an input without a password") {
        `when`("the flow decides") {
            val decision = AuthPasswordFlow.decide(AuthPasswordInput())

            then("the state is unchanged") {
                decision shouldBe AuthPasswordDecision.Unchanged
            }
        }
    }

    given("an input with a password") {
        `when`("the flow decides") {
            val decision = AuthPasswordFlow.decide(AuthPasswordInput("hunter2"))

            then("it is named for the handler to check against the enrollment") {
                decision shouldBe AuthPasswordDecision.Check("hunter2")
            }
        }
    }
})
