package com.example.identity.core.orchestrator.domain.journey.state

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf

/** The one device question in its wordings: what differs is only what declining is called. */
class DeviceLinkQuestionTest : BehaviorSpec({

    given("the wordings of the device question") {
        val offer = DeviceLinkQuestion(DeviceLinkQuestion.Wording.OFFER).question.shouldBeInstanceOf<Question.Confirm>()
        val rebind = DeviceLinkQuestion(DeviceLinkQuestion.Wording.REBIND).question.shouldBeInstanceOf<Question.Confirm>()
        val rebindOrCancel = DeviceLinkQuestion(DeviceLinkQuestion.Wording.REBIND_OR_CANCEL).question.shouldBeInstanceOf<Question.Confirm>()

        then("an offer is harmless, moving a link is destructive") {
            offer.destructive shouldBe false
            rebind.destructive shouldBe true
        }

        then("the two rebind wordings differ only in what declining is called") {
            rebind.cancelLabel shouldBe offer.cancelLabel
            rebind.copy(cancelLabel = rebindOrCancel.cancelLabel) shouldBe rebindOrCancel
        }

        then("the 'Enrollment zuerst' twin asks the same rebind question") {
            RegisterEnrollFirstState.EnrollFirstConfirmDeviceRebind.question shouldBe rebind
        }
    }
})
