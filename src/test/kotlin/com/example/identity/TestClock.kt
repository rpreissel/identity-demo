package com.example.identity

import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Die feste Uhr der Unit-Tests: Dienst und Testdaten lesen dieselbe Zeit, statt je einmal die
 * Systemuhr zu fragen (DPoP-demo-xzl1). Tests mit Spring-Kontext nutzen die Clock-Bean aus
 * ClockConfig und nicht diese Uhr.
 */
val TEST_CLOCK: Clock = Clock.fixed(Instant.parse("2026-03-02T09:30:00Z"), ZoneOffset.UTC)

/** Der Zeitpunkt von [TEST_CLOCK]. */
val TEST_NOW: Instant = TEST_CLOCK.instant()
