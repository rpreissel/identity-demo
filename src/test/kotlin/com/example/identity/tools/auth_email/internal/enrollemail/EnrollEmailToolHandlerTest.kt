package com.example.identity.tools.auth_email.internal.enrollemail

import com.example.identity.contract.tool_api.InMemoryToolSessionData
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures.tool
import com.example.identity.contract.tool_api.ToolOutcome
import com.example.identity.contract.tool_api.directory.EMAIL_ANCHOR_ENROLLMENT
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import java.util.UUID

/**
 * Pure unit test: no Spring context, the session data kept in memory. enroll-email is a one-shot,
 * so start and every later read return the same Enrolled outcome referencing the EMAIL anchor.
 */
class EnrollEmailToolHandlerTest : BehaviorSpec({

    val sessions = InMemoryToolSessionData()
    val handler = EnrollEmailToolHandler(sessions)

    // No amr: control of the address was proven by confirm-email, not in this run.
    val expected = ToolOutcome.Completed.Enrolled(
        enrollmentRef = EMAIL_ANCHOR_ENROLLMENT,
        amr = emptyList(),
    )

    given("no enroll-email tool session yet") {
        `when`("an enroll-email run begins") {
            val toolSessionId = ToolSessionId(UUID.randomUUID())
            val outcome = handler.start(toolSessionId)

            then("it records the run and completes at once with the EMAIL anchor as credential") {
                sessions.stored<EnrollEmailToolSession>(toolSessionId) shouldBe EnrollEmailToolSession()
                outcome shouldBe expected
            }
        }
    }

    given("a completed enroll-email tool session") {
        val toolSessionId = ToolSessionId(UUID.randomUUID())
        sessions.save(toolSessionId, EnrollEmailToolSession())

        `when`("it is read again") {
            val outcome = handler.read(toolSessionId)

            then("it returns the same Enrolled outcome") {
                outcome shouldBe expected
            }
        }
    }

    given("no enroll-email tool session") {
        val unknownId = ToolSessionId(UUID.randomUUID())

        `when`("it is read") {
            val result = runCatching { handler.read(unknownId) }

            then("it fails as a programming error, not with a made-up outcome") {
                shouldThrow<IllegalStateException> { result.getOrThrow() }
            }
        }
    }
})
