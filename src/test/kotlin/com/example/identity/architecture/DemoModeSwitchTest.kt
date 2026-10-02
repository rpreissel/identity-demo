package com.example.identity.architecture

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File

private const val MAIN = "src/main/kotlin/com/example/identity"

/**
 * `demo.mode` is read in one place (ADR-36): the module `demo_mode` turns it into `DemoMode`,
 * `OnlyInDemoMode` and `OutsideDemoMode`. A second reader could give it a different default, and
 * a module that must not know the demo could read it past its dependencies. ArchUnit does not see
 * property names, so this reads the sources.
 */
class DemoModeSwitchTest : BehaviorSpec({

    given("the main sources outside demo/demo_mode") {
        val outsideDemoMode = { file: File -> "/demo/demo_mode/" !in file.invariantSeparatorsPath }

        then("they are read at all") {
            kotlinSources(MAIN, outsideDemoMode).size shouldBeGreaterThan 100
        }
        then("none reads the property itself") {
            sourceLinesMatching(MAIN, Regex(""""demo\.mode"|\$\{demo\.mode"""), outsideDemoMode).shouldBeEmpty()
        }
    }
})
