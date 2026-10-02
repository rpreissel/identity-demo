package com.example.identity.tools.auth_password.internal.authpasswordlookup

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class AuthPasswordLookupFlowTest : BehaviorSpec({

    given("an input with neither email nor password") {
        `when`("the flow decides") {
            val decision = AuthPasswordLookupFlow.decide(AuthPasswordLookupInput())

            then("both are reported missing") {
                decision shouldBe AuthPasswordLookupDecision.Incomplete(listOf("email", "password"))
            }
        }
    }

    given("an input with only the email") {
        `when`("the flow decides") {
            val decision = AuthPasswordLookupFlow.decide(AuthPasswordLookupInput(email = "max@example.com"))

            then("password is reported missing") {
                decision shouldBe AuthPasswordLookupDecision.Incomplete(listOf("password"))
            }
        }
    }

    given("an input with only the password") {
        `when`("the flow decides") {
            val decision = AuthPasswordLookupFlow.decide(AuthPasswordLookupInput(password = "hunter2"))

            then("email is reported missing") {
                decision shouldBe AuthPasswordLookupDecision.Incomplete(listOf("email"))
            }
        }
    }

    given("an input with both") {
        `when`("the flow decides") {
            val decision = AuthPasswordLookupFlow.decide(AuthPasswordLookupInput(email = "max@example.com", password = "hunter2"))

            then("a check is requested with both values") {
                decision shouldBe AuthPasswordLookupDecision.Check("max@example.com", "hunter2")
            }
        }
    }
})
