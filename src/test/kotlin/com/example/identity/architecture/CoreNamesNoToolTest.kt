package com.example.identity.architecture

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.ints.shouldBeGreaterThan
import java.io.File

/**
 * The core knows no tool by name (docs/03-tool-architektur.md #4, docs/04-orchestrierung.md): an
 * offer comes from a role, a descriptor from `(method, role)`. A `ToolId("…")` literal in the core
 * would name one - ArchUnit sees calls, not string literals, so this reads the sources.
 */
class CoreNamesNoToolTest : BehaviorSpec({

    given("the sources of core/") {
        val core = File("src/main/kotlin/com/example/identity/core")
        val literal = Regex("""ToolId\(\s*"""")

        val sources = core.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()

        then("it reads them at all - an empty walk would prove nothing") {
            sources.size shouldBeGreaterThan 100
        }
        then("no ToolId is built from a literal") {
            sources.asSequence()
                .flatMap { file -> file.readLines().mapIndexedNotNull { i, line -> if (literal.containsMatchIn(line)) "${file.path}:${i + 1}" else null } }
                .toList()
                .shouldBeEmpty()
        }
    }
})
