package com.example.identity.core.orchestrator

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.web.client.HttpClientErrorException

/**
 * Health and metrics (docs/07-betrieb.md Abschnitt 7): on the management port, never on the public one - and,
 * for the demo, readable through the welcome page's server-info.
 */
class OperationsIntegrationTest : IntegrationTestSupport() {

    @Value("\${local.management.port}")
    private var managementPort: Int = 0

    init {
        given("the management port") {
            `when`("readiness and prometheus are read") {
                val readiness = restTemplate.getForObject("http://localhost:$managementPort/actuator/health/readiness", Map::class.java)!!
                val scrape = restTemplate.getForObject("http://localhost:$managementPort/actuator/prometheus", String::class.java)!!

                then("readiness depends on the database and is UP") {
                    readiness["status"] shouldBe "UP"
                }

                then("prometheus carries this project's own meters") {
                    scrape shouldContain "identity_events_incomplete"
                }
            }
        }

        given("the public port") {
            `when`("the actuator health is requested there") {
                val result = runCatching {
                    restTemplate.getForObject("http://localhost:$port/actuator/health", String::class.java)
                }

                then("it is not found - health and metrics never leave through the public route") {
                    shouldThrow<HttpClientErrorException> { result.getOrThrow() }.statusCode shouldBe HttpStatus.NOT_FOUND
                }
            }
        }

        given("the welcome page's server info") {
            `when`("it is read") {
                @Suppress("UNCHECKED_CAST")
                val operations = restTemplate.getForObject("http://localhost:$port/orchestrator/demo/server-info", Map::class.java)!!["operations"] as Map<String, Any?>

                then("it shows the same health and the project's meters") {
                    operations["status"] shouldBe "UP"
                    @Suppress("UNCHECKED_CAST")
                    val components = (operations["components"] as List<Map<String, Any?>>).associate { it["name"] to it["status"] }
                    components["db"] shouldBe "UP"
                    @Suppress("UNCHECKED_CAST")
                    (operations["metrics"] as List<Map<String, Any?>>).map { it["name"] } shouldContain "identity.events.incomplete"
                }
            }
        }
    }
}
