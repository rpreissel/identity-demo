package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.orchestrator.domain.JourneyId
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import java.time.Instant
import java.util.UUID

/** A tool's working data in its ToolSession row: typed, namespaced, and readable across versions. */
class ToolSessionDataServiceTest : BehaviorSpec({

    data class Step(val code: String, val tries: Int = 0, val issuedAt: Instant? = null)
    data class Other(val value: String)

    val id = ToolSessionId(UUID.randomUUID())
    val codec = ToolSessionDataCodec()
    val keys = InMemoryRetentionClassKeys()
    fun service(row: ToolSession?) = ToolSessionDataService(
        mockk<ToolSessionRepository> { every { findByToolSessionId(id) } returns row },
        codec,
        keys.keys,
        keys.wrapping,
    )
    fun row() = ToolSession(JourneyId(UUID.randomUUID()), Instant.now().plusSeconds(600), Instant.now())

    given("a session the tool has not saved anything for") {
        then("there is no state yet") {
            service(row()).load(id, Step::class) shouldBe null
        }
    }

    given("a session holding a state") {
        val session = row()
        val store = service(session)
        val issued = Instant.parse("2026-10-03T08:00:00Z")
        store.save(id, Step("abc", 1, issued))

        then("it comes back unchanged, under its module and class") {
            store.load(id, Step::class) shouldBe Step("abc", 1, issued)
            session.dataType shouldBe "orchestrator.Step"
        }
        then("reading it as another type is a contract error") {
            shouldThrow<IllegalStateException> { store.load(id, Other::class) }.message shouldContain "holds orchestrator.Step"
        }
        then("replacing it with another type is refused") {
            shouldThrow<IllegalStateException> { store.save(id, Other("x")) }.message shouldContain "cannot take orchestrator.Other"
        }
    }

    given("a state another version wrote") {
        val session = row().apply {
            dataType = "orchestrator.Step"
            val key = keys.keys.currentToolSessionKey()
            dataKeyId = key.keyId
            data = keys.wrapping.seal(key.key, "data-key:${key.keyId}", "tool-session:${id.value}:orchestrator.Step".toByteArray(), """{"code":"abc","addedLater":true}""".toByteArray())
        }

        then("an unknown field is skipped and a missing one takes its default") {
            service(session).load(id, Step::class) shouldBe Step("abc", 0, null)
        }
    }

    given("an id without a tool session") {
        then("saving is refused") {
            shouldThrow<IllegalStateException> { service(null).save(id, Step("abc")) }.message shouldContain "Unknown tool session"
        }
    }
})
