package com.example.identity.core.orchestrator.domain

import com.example.identity.contract.texts.Text
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain

/** The detail reaches the log; what a client put into a path or query must not forge a log line there. */
class OrchestratorExceptionTest : BehaviorSpec({

    given("a detail carrying a client value with a line break") {
        `when`("the exception is built") {
            val ex = OrchestratorException.notFound(Text("Unknown tool"), "toolId=foo\n2026-10-03 INFO forged line\r")

            then("the message stays on one line") {
                ex.message!! shouldNotContain "\n"
                ex.message!! shouldNotContain "\r"
                ex.message!! shouldNotContain " "
                ex.message shouldBe "Unknown tool (toolId=foo?2026-10-03 INFO forged?line?)"
            }
        }
    }

    given("a detail longer than the bound") {
        `when`("the exception is built") {
            val ex = OrchestratorException.invalidState(Text("x"), "intent=" + "a".repeat(10_000))

            then("the message keeps only the bounded start") {
                ex.message!!.length shouldBe "x (".length + OrchestratorException.MAX_DETAIL_LENGTH + "...)".length
            }
        }
    }
})
