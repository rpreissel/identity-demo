package com.example.identity.core.account.application

import com.example.identity.core.account.infrastructure.PERSON_CHANGE_EXECUTOR
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
 * pair as the real one; the real listener's routing is pinned in PersonChangeListenerRoutingTest.
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
        given("a person-change listener that holds the lane until released") {
            `when`("three changes are committed in separate transactions at once") {
                repeat(3) { i -> transactions.executeWithoutResult { events.publishEvent(ProbeChange(i)) } }
                val queuedBehindFirst = runCatching {
                    await().atMost(5, TimeUnit.SECONDS).until { lane.queueSize == 2 }
                }.isSuccess
                val startedWhileHeld = probe.started.get()
                probe.release.countDown()
                val allDone = probe.done.await(5, TimeUnit.SECONDS)

                then("the other two wait in the queue behind the first instead of running beside it") {
                    queuedBehindFirst shouldBe true
                    startedWhileHeld shouldBe 1
                }
                then("all three run one after another on the person-change thread") {
                    allDone shouldBe true
                    probe.threads shouldHaveSize 3
                    probe.threads.forEach { it shouldStartWith "person-change-" }
                    probe.maxConcurrent.get() shouldBe 1
                }
            }
        }

        given("any other @Async method") {
            `when`("it is called") {
                val thread = probe.plainAsync().get()

                then("it still runs on Spring Boot's shared pool, not on the single lane") {
                    // Without spring.task.execution.mode=force, Boot drops its own executor as soon as
                    // personChangeExecutor exists - hence the positive check on Boot's "task-" prefix.
                    thread shouldStartWith "task-"
                }
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
