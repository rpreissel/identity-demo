package com.example.identity.architecture

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * Specs act in `when` and check in `then` (AGENTS.md, Testregeln). The capitalised
 * `Given`/`When`/`Then` containers belong to a style where a `then` acts itself; in a Spring spec
 * such a `then` would act after the database wipe, so they are forbidden.
 */
class GivenWhenThenStyleTest : BehaviorSpec({

    given("the test sources") {
        then("none of them uses the capitalised Given/When/Then") {
            sourceLinesMatching("src/test/kotlin", Regex("""\b(Given|When|Then)\(""")).shouldBeEmpty()
        }
    }
})
