package com.example.identity.architecture

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import java.io.File

/**
 * Ein Spec mit `resetPerWhen = true` handelt im `when` und prüft im `then` (AGENTS.md, Testregeln).
 * The capitalised `Given(`/`When(`/`Then(` is the older style, where a `then` acts itself; mixed
 * into such a spec it would act after the wipe, so it is forbidden there.
 */
class ResetPerWhenStyleTest : BehaviorSpec({

    given("the test sources that opt into resetPerWhen") {
        val optIn = Regex("""override\s+val\s+resetPerWhen\s*=\s*true""")
        val oldStyle = Regex("""\b(Given|When|Then)\(""")

        val specs = File("src/test/kotlin").walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { optIn.containsMatchIn(it.readText()) }
            .toList()

        then("it finds them at all - an empty walk would prove nothing") {
            specs.shouldNotBeEmpty()
        }
        then("none of them uses the capitalised Given/When/Then") {
            specs.asSequence()
                .flatMap { file -> file.readLines().mapIndexedNotNull { i, line -> if (oldStyle.containsMatchIn(line)) "${file.path}:${i + 1}" else null } }
                .toList()
                .shouldBeEmpty()
        }
    }
})
