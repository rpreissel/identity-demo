package com.example.identity.architecture

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan

private const val CORE = "src/main/kotlin/com/example/identity/core"

/**
 * The core knows no tool by name (docs/03-tool-architektur.md #7, docs/04-orchestrierung.md): an
 * offer comes from a role, a descriptor from `(method, role)`. A `ToolId("…")` literal in the core
 * would name one - ArchUnit sees calls, not string literals, so this reads the sources.
 */
class CoreNamesNoToolTest : BehaviorSpec({

    given("the sources of core/") {
        then("they are read at all - an empty walk would prove nothing") {
            kotlinSources(CORE).size shouldBeGreaterThan 100
        }
        then("no ToolId is built from a literal") {
            sourceLinesMatching(CORE, Regex("""ToolId\(\s*"""")).shouldBeEmpty()
        }
    }
})
