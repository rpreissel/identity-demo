package com.example.identity.core.orchestrator.schema

import com.example.identity.demo.demo_mode.DemoMode
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain

/** The demo personas are migrated in demo mode only. */
class ModuleMigrationLocationsTest : BehaviorSpec({

    fun locations(demoMode: Boolean): List<String> = ModuleMigrationLocations(DemoMode(demoMode)).moduleLocations()

    given("demo mode") {
        then("every module folder runs, demo_seed and demo_views included") {
            locations(true) shouldContain "classpath:db/migration/demo_seed"
            locations(true) shouldContain "classpath:db/migration/demo_views"
            locations(true) shouldContain "classpath:db/migration/account"
        }
    }

    given("demo mode off") {
        then("demo_seed and demo_views are left out, every other module still runs") {
            locations(false) shouldNotContain "classpath:db/migration/demo_seed"
            locations(false) shouldNotContain "classpath:db/migration/demo_views"
            locations(false) shouldContain "classpath:db/migration/account"
        }
    }
})
