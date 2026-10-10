package com.example.identity.tools.auth_invite.internal

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class AuthInviteLookupFlowTest : BehaviorSpec({

    given("a KVNR and a one-time password") {
        `when`("the flow decides") {
            val decision = AuthInviteLookupFlow.decide(AuthInviteInput(kvnr = "A123456789", code = "ABCD-EFGH-JKMN"))

            then("it checks the code as given") {
                decision shouldBe AuthInviteDecision.Check("ABCD-EFGH-JKMN")
            }
        }
    }

    given("a Partnernummer instead of a KVNR, and a one-time password") {
        `when`("the flow decides") {
            val decision = AuthInviteLookupFlow.decide(AuthInviteInput(kvnr = " ", partnerNumber = "P000000004", code = "ABCD-EFGH-JKMN"))

            then("the number counts as given") {
                decision shouldBe AuthInviteDecision.Check("ABCD-EFGH-JKMN")
            }
        }
    }

    given("a number without a one-time password") {
        `when`("the flow decides") {
            val decision = AuthInviteLookupFlow.decide(AuthInviteInput(kvnr = "A123456789", code = " "))

            then("only the code is missing") {
                decision shouldBe AuthInviteDecision.Incomplete(listOf("code"))
            }
        }
    }

    given("a one-time password without a number") {
        `when`("the flow decides") {
            val decision = AuthInviteLookupFlow.decide(AuthInviteInput(code = "ABCD-EFGH-JKMN"))

            then("kvnr stands for the missing number") {
                decision shouldBe AuthInviteDecision.Incomplete(listOf("kvnr"))
            }
        }
    }

    given("an empty input") {
        `when`("the flow decides") {
            val decision = AuthInviteLookupFlow.decide(AuthInviteInput())

            then("both fields are missing") {
                decision shouldBe AuthInviteDecision.Incomplete(listOf("kvnr", "code"))
            }
        }
    }
})
