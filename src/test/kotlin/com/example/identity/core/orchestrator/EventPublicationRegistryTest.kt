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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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
    @Autowired private lateinit var listener: FailingListenerConfig

    init {
        given("a module listener that fails") {
            `when`("an event is published inside a transaction") {
                val marker = UUID.randomUUID().toString()

                // Recording it with the commit makes the registry an outbox rather than a log written afterwards.
                transactions.executeWithoutResult { events.publishEvent(ProbeEvent(marker)) }
                // The listener is @Async: the row is only meaningful once it has run and failed.
                val invoked = listener.invoked.await(5, TimeUnit.SECONDS)

                then("the listener ran") {
                    invoked shouldBe true
                }
                then("its publication stays open in orchestrator.event_publication") {
                    jdbcTemplate.queryForObject(
                        """
                        select count(*) from orchestrator.event_publication
                        where completion_date is null and serialized_event like ?
                        """.trimIndent(),
                        Long::class.java, "%$marker%"
                    ) shouldBe 1L
                }
            }
        }
    }

    /** A plain data class so the registry's Jackson serializer can store and restore it. */
    data class ProbeEvent(val marker: String)

    /**
     * The listener sits on the configuration class so that it can be proxied: `plugin.spring`
     * opens `@TestConfiguration` classes, but not a plain class handed out by `@Bean`.
     */
    @TestConfiguration
    class FailingListenerConfig {
        val invoked = CountDownLatch(1)

        @ApplicationModuleListener
        fun on(event: ProbeEvent) {
            invoked.countDown()
            // Throwing leaves the publication open for resubmission, as a failing Keycloak call does.
            error("probe failure for ${event.marker}")
        }
    }
}
