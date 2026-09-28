package com.example.identity.core.orchestrator.kc

import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Wartet, bis [probe] ohne Exception durchlaeuft, bevor der Orchestrator Keycloak migriert. In
 * Kubernetes gibt es keine Startreihenfolge, also wartet die Anwendung selbst auf ihre
 * Abhaengigkeit; Compose verlaesst sich ebenfalls darauf. Nach [timeout] wirft sie die letzte
 * Exception weiter: dann stimmt eher die Adresse nicht.
 */
class AwaitReachable(
    private val timeout: Duration,
    private val now: () -> Instant,
    private val interval: Duration = Duration.ofSeconds(2),
    private val sleep: (Duration) -> Unit = { Thread.sleep(it.toMillis()) },
) {
    private val log = LoggerFactory.getLogger(AwaitReachable::class.java)

    fun await(what: String, probe: () -> Unit) {
        val deadline = now().plus(timeout)
        var attempts = 0
        while (true) {
            try {
                probe()
                if (attempts > 0) log.info("{} ist erreichbar (nach {} Versuchen)", what, attempts + 1)
                return
            } catch (e: Exception) {
                attempts++
                if (!now().isBefore(deadline)) {
                    throw IllegalStateException("$what nach $timeout nicht erreichbar", e)
                }
                // Nicht jeden Versuch loggen, damit das Log lesbar bleibt.
                if (attempts == 1 || attempts % 10 == 0) log.info("Warte auf {}: {}", what, e.message)
                sleep(interval)
            }
        }
    }
}
