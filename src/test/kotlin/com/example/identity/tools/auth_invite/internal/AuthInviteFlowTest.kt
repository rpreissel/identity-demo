package com.example.identity.tools.auth_invite.internal

import com.example.identity.contract.tool_api.MissingFields
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe

class AuthInviteFlowTest : BehaviorSpec({

    given("a KVNR and a one-time password") {
        `when`("the flow decides") {
            val decision = AuthInviteFlow.decide(AuthInviteInput(kvnr = "A123456789", code = "ABCD-EFGH-JKMN"))

            then("it checks the code as given") {
                decision shouldBe AuthInviteDecision.Check("ABCD-EFGH-JKMN")
            }
        }
    }

    given("a Partnernummer instead of a KVNR, and a one-time password") {
        `when`("the flow decides") {
            val decision = AuthInviteFlow.decide(AuthInviteInput(kvnr = " ", partnerNumber = "P000000004", code = "ABCD-EFGH-JKMN"))

            then("the number counts as given") {
                decision shouldBe AuthInviteDecision.Check("ABCD-EFGH-JKMN")
            }
        }
    }

    given("a number without a one-time password") {
        `when`("the flow decides") {
            val decision = AuthInviteFlow.decide(AuthInviteInput(kvnr = "A123456789", code = " "))

            then("only the code is missing") {
                decision shouldBe AuthInviteDecision.Incomplete(listOf("code"))
            }
        }
    }

    given("a one-time password without a number") {
        `when`("the flow decides") {
            val decision = AuthInviteFlow.decide(AuthInviteInput(code = "ABCD-EFGH-JKMN"))

            then("kvnr stands for the missing number") {
                decision shouldBe AuthInviteDecision.Incomplete(listOf("kvnr"))
            }
        }
    }

    given("an empty input") {
        `when`("the flow decides") {
            val decision = AuthInviteFlow.decide(AuthInviteInput())

            then("both fields are missing") {
                decision shouldBe AuthInviteDecision.Incomplete(listOf("kvnr", "code"))
            }
        }
    }

    given("no missing fields named") {
        `when`("the step is described") {
            val described = AuthInviteFlow.describe()

            then("it is step auth, asking for all fields") {
                described shouldBe ("auth" to MissingFields(listOf("kvnr", "code")))
            }
        }
    }
})
