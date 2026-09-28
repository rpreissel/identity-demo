package com.example.identity.architecture

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File

/**
 * `demo.mode` is read in one place (ADR-36): the module `demo_mode` turns it into `DemoMode`,
 * `OnlyInDemoMode` and `OutsideDemoMode`. A second reader could give it a different default, and
 * a module that must not know the demo could read it past its dependencies. ArchUnit does not see
 * property names, so this reads the sources.
 */
class DemoModeSwitchTest : BehaviorSpec({

    given("the main sources outside demo/demo_mode") {
        val sources = File("src/main/kotlin/com/example/identity").walkTopDown()
            .filter { it.isFile && it.extension == "kt" && "/demo/demo_mode/" !in it.invariantSeparatorsPath }
            .toList()
        val read = Regex(""""demo\.mode"|\$\{demo\.mode""")

        then("it reads them at all") {
            sources.size shouldBeGreaterThan 100
        }
        then("none reads the property itself") {
            sources.flatMap { file -> file.readLines().mapIndexedNotNull { i, line -> if (read.containsMatchIn(line)) "${file.path}:${i + 1}" else null } }
                .shouldBeEmpty()
        }
    }
})
