package com.example.identity.architecture

import com.example.identity.core.orchestrator.SCHEDULED_JOBS
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.shouldBe
import org.springframework.scheduling.annotation.Scheduled

/**
 * Keeps [SCHEDULED_JOBS] - and with it the single-instance check and docs/07-betrieb.md Abschnitt 3b -
 * complete: a new `@Scheduled` method that is not listed, or a listed job that is gone, fails here.
 */
class ScheduledJobsTest : BehaviorSpec({
    given("the application's classes") {
        then("every class with a @Scheduled method is a declared job, and every declared job has one") {
            val scheduled = MAIN_CLASSES
                .filter { cls -> cls.methods.any { it.isAnnotatedWith(Scheduled::class.java) } }
                .map { it.simpleName }
                .toSet()
            scheduled shouldBe SCHEDULED_JOBS.keys
        }
    }
})
