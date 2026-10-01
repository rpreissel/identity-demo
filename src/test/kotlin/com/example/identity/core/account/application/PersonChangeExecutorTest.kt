package com.example.identity.core.account.application

import com.example.identity.core.account.application.PersonChangeListener
import com.example.identity.core.account.infrastructure.PERSON_CHANGE_EXECUTOR
import com.example.identity.core.account.infrastructure.PersonChangeExecutorConfig
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import org.awaitility.Awaitility.await
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.annotation.Import
import org.springframework.core.annotation.AnnotatedElementUtils
import org.springframework.modulith.events.ApplicationModuleListener
import org.springframework.scheduling.annotation.Async
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * The directory's change events run one at a time (PersonChangeExecutorConfig) - and that lane does
 * not swallow every other `@Async` in the application. A probe listener carries the same annotation
 * pair as the real one; the real listener is pinned separately.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PersonChangeExecutorTest.ProbeConfig::class)
class PersonChangeExecutorTest : BehaviorSpec() {

    @Autowired private lateinit var events: ApplicationEventPublisher
    @Autowired private lateinit var transactions: TransactionTemplate
    @Autowired private lateinit var probe: ProbeConfig
    @Autowired @Qualifier(PERSON_CHANGE_EXECUTOR) private lateinit var lane: ThreadPoolTaskExecutor

    init {
        given("several changes committed in separate transactions at once") {
            then("they run one after another on the person-change thread") {
                repeat(3) { i -> transactions.executeWithoutResult { events.publishEvent(ProbeChange(i)) } }

                // The first change holds the lane until released: the other two must be queued
                // behind it, not running beside it.
                await().atMost(5, TimeUnit.SECONDS).until { lane.queueSize == 2 }
                probe.started.get() shouldBe 1
                probe.release.countDown()

                probe.done.await(5, TimeUnit.SECONDS) shouldBe true
                probe.threads shouldHaveSize 3
                probe.threads.forEach { it shouldStartWith "person-change-" }
                probe.maxConcurrent.get() shouldBe 1
            }
        }

        given("any other @Async method") {
            then("it still runs on Spring Boot's shared pool, not on the single lane") {
                // Without spring.task.execution.mode=force, Boot drops its own executor as soon as
                // personChangeExecutor exists - hence the positive check on Boot's "task-" prefix.
                probe.plainAsync().get() shouldStartWith "task-"
            }
        }

        given("the real PersonChangeListener") {
            then("is routed to the person-change lane") {
                val method = PersonChangeListener::class.java.methods.single { it.name == "onPersonChanged" }
                AnnotatedElementUtils.findMergedAnnotation(method, Async::class.java)?.value shouldBe PERSON_CHANGE_EXECUTOR
            }
        }
    }

    data class ProbeChange(val index: Int)

    @TestConfiguration
    class ProbeConfig {
        val threads: MutableList<String> = Collections.synchronizedList(mutableListOf())
        val maxConcurrent = AtomicInteger()
        val started = AtomicInteger()
        val release = CountDownLatch(1)
        val done = CountDownLatch(3)
        private val running = AtomicInteger()

        @ApplicationModuleListener
        @Async(PERSON_CHANGE_EXECUTOR)
        fun on(event: ProbeChange) {
            val now = running.incrementAndGet()
            maxConcurrent.accumulateAndGet(now, ::maxOf)
            started.incrementAndGet()
            release.await(5, TimeUnit.SECONDS)
            threads += Thread.currentThread().name
            running.decrementAndGet()
            done.countDown()
        }

        @Async
        fun plainAsync(): CompletableFuture<String> = CompletableFuture.completedFuture(Thread.currentThread().name)
    }
}
