package com.example.identity.architecture

import com.example.identity.ClockConfig
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import java.time.Clock

/**
 * The application reads the time only from the `Clock` bean ([ClockConfig]), so a test can set it
 * (docs/08-projektrahmen.md Abschnitt 3). Forbidden elsewhere is every call that reads the system
 * clock itself: `now()` without a `Clock`, `System.currentTimeMillis()`, `Clock.system*()` and
 * `Date()`. `System.nanoTime()` stays allowed: it measures durations, not the time of day.
 */
class ClockArchitectureTest : BehaviorSpec({

    // Without kcmigrate: a library outside the application, run once per start (08-projektrahmen M22).
    val main = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.example.identity")
        .filterNot { it.packageName.startsWith("com.example.identity.kcmigrate") }

    fun readsSystemClock(call: JavaCall<*>): Boolean {
        val owner = call.targetOwner.name
        val params = call.target.rawParameterTypes.map { it.name }
        return when {
            owner.startsWith("java.time.") && call.name == "now" -> params != listOf(Clock::class.java.name)
            owner == Clock::class.java.name -> call.name.startsWith("system")
            owner == System::class.java.name -> call.name == "currentTimeMillis"
            owner == java.util.Date::class.java.name -> call.name == "<init>" && params.isEmpty()
            else -> false
        }
    }

    given("the application classes") {
        then("they are imported") {
            main.shouldNotBeEmpty()
        }

        then("only ClockConfig reads the system clock") {
            val offenders = main
                .filterNot { it.name == ClockConfig::class.java.name }
                .flatMap { it.methodCallsFromSelf + it.constructorCallsFromSelf }
                .filter(::readsSystemClock)
                .map { "${it.originOwner.name}.${it.origin.name} -> ${it.targetOwner.simpleName}.${it.name}" }
                .distinct()
            offenders.shouldBeEmpty()
        }

        then("the rule catches a call it forbids") {
            val calls = ClassFileImporter().importClasses(ReadsTheSystemClock::class.java)
                .flatMap { it.methodCallsFromSelf + it.constructorCallsFromSelf }
                .filter(::readsSystemClock)
            calls.shouldNotBeEmpty()
        }
    }
})

/** The counterexample the rule must catch. */
private class ReadsTheSystemClock {
    @Suppress("unused")
    fun now(): java.time.Instant = java.time.Instant.now()
}
