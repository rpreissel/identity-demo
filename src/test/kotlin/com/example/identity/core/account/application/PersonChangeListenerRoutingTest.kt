package com.example.identity.core.account.application

import com.example.identity.core.account.infrastructure.PERSON_CHANGE_EXECUTOR
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.scheduling.annotation.Async

/**
 * The real listener sits on the person-change lane; that the lane runs one change at a time is
 * PersonChangeExecutorTest's part.
 */
class PersonChangeListenerRoutingTest : BehaviorSpec({

    given("the real PersonChangeListener") {
        val method = PersonChangeListener::class.java.methods.single { it.name == "onPersonChanged" }

        then("is routed to the person-change lane") {
            AnnotatedElementUtils.findMergedAnnotation(method, Async::class.java)?.value shouldBe PERSON_CHANGE_EXECUTOR
        }
    }
})
