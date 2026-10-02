package com.example.identity.tools.auth_password.internal.enrollpassword

import com.example.identity.tools.auth_password.internal.PasswordPolicy

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class EnrollPasswordFlowTest : BehaviorSpec({

    given("an input without a password") {
        `when`("the flow decides") {
            val decision = EnrollPasswordFlow.decide(EnrollPasswordInput())

            then("the state is unchanged") {
                decision shouldBe EnrollPasswordDecision.Unchanged
            }
        }
    }

    given("a password shorter than the minimum length") {
        `when`("the flow decides") {
            val decision = EnrollPasswordFlow.decide(EnrollPasswordInput("short"))

            then("it is rejected as too short") {
                decision shouldBe EnrollPasswordDecision.Rejected(PasswordPolicy.Rejection.TOO_SHORT)
            }
        }
    }

    given("a password longer than the maximum length") {
        `when`("the flow decides") {
            val decision = EnrollPasswordFlow.decide(EnrollPasswordInput("x".repeat(129)))

            then("it is rejected as too long") {
                decision shouldBe EnrollPasswordDecision.Rejected(PasswordPolicy.Rejection.TOO_LONG)
            }
        }
    }

    given("one of the passwords every guessing attack tries first") {
        `when`("it is submitted capitalized") {
            val decision = EnrollPasswordFlow.decide(EnrollPasswordInput("Passwort123"))

            then("it is rejected as too common") {
                decision shouldBe EnrollPasswordDecision.Rejected(PasswordPolicy.Rejection.TOO_COMMON)
            }
        }

        `when`("it is submitted in upper case") {
            val decision = EnrollPasswordFlow.decide(EnrollPasswordInput("PASSWORT123"))

            then("it is rejected as too common all the same") {
                decision shouldBe EnrollPasswordDecision.Rejected(PasswordPolicy.Rejection.TOO_COMMON)
            }
        }
    }

    given("a password meeting the minimum length") {
        `when`("the flow decides") {
            val decision = EnrollPasswordFlow.decide(EnrollPasswordInput("correct-horse-battery"))

            then("enrollment is requested") {
                decision shouldBe EnrollPasswordDecision.Enroll("correct-horse-battery")
            }
        }
    }
})
