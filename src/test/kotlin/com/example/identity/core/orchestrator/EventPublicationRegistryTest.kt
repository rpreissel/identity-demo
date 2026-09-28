package com.example.identity.core.orchestrator

import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.util.UUID

/**
 * Proves the Event Publication Registry works in this application, not merely on the classpath.
 * It makes the Keycloak mirror recoverable (docs/07-betrieb.md Abschnitt 3a). A misconfigured
 * table, serializer or listener fails silently, so the test drives the failure case: a listener
 * that throws must leave an open row behind.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(EventPublicationRegistryTest.FailingListenerConfig::class)
class EventPublicationRegistryTest : BehaviorSpec() {

    @Autowired private lateinit var events: ApplicationEventPublisher
    @Autowired private lateinit var transactions: TransactionTemplate
    @Autowired private lateinit var jdbcTemplate: JdbcTemplate

    init {
        given("a module listener that fails") {
            then("its publication stays open in orchestrator.event_publication") {
                val marker = UUID.randomUUID().toString()

                // Published inside a transaction: recording it with the commit makes the registry an
                // outbox rather than a log written afterwards.
                transactions.executeWithoutResult { events.publishEvent(ProbeEvent(marker)) }

                // The listener is @Async, so the row appears shortly after the commit.
                val open = eventually {
                    jdbcTemplate.queryForObject(
                        """
                        select count(*) from orchestrator.event_publication
                        where completion_date is null and serialized_event like ?
                        """.trimIndent(),
                        Long::class.java, "%$marker%"
                    ) ?: 0L
                }
                open shouldBe 1L
            }
        }
    }

    /** Polls rather than sleeping a fixed span: the listener runs on another thread. */
    private fun eventually(block: () -> Long): Long {
        val deadline = System.currentTimeMillis() + 5_000
        var last = 0L
        while (System.currentTimeMillis() < deadline) {
            last = block()
            if (last > 0L) return last
            Thread.sleep(50)
        }
        return last
    }

    /** A plain data class so the registry's Jackson serializer can store and restore it. */
    data class ProbeEvent(val marker: String)

    /**
     * The listener sits on the configuration class so that it can be proxied: `plugin.spring`
     * opens `@TestConfiguration` classes, but not a plain class handed out by `@Bean`.
     */
    @TestConfiguration
    class FailingListenerConfig {

        @ApplicationModuleListener
        fun on(event: ProbeEvent) {
            // Throwing leaves the publication open for resubmission, as a failing Keycloak call does.
            error("probe failure for ${event.marker}")
        }
    }
}
