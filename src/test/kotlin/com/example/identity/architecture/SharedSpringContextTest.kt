package com.example.identity.architecture

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * A spec that declares its own mocked or spied bean gets a Spring context of its own, which costs a
 * start of several seconds. Fakes live in `SharedSpringContext` (docs/13-ausfuehren.md).
 */
class SharedSpringContextTest : BehaviorSpec({

    given("the test sources") {
        then("only SharedSpringContext declares mocked or spied beans") {
            sourceLinesMatching("src/test/kotlin", Regex("""@(MockkBean|MockkSpyBean|MockBean|SpyBean|MockitoBean)\b""")) { it.name != "SharedSpringContext.kt" }
                .shouldBeEmpty()
        }
    }
})
