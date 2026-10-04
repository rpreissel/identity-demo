package com.example.identity.core.orchestrator.api.v1

import com.example.identity.contract.tool_api.envelope.API_V1
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.yaml.snakeyaml.Yaml
import java.nio.file.Files
import java.nio.file.Path

/**
 * Der App-Vertrag enthaelt genau das, was unter [API_V1] liegt, im eingecheckten und im
 * veroeffentlichten Stand; die versionierten Teile zudem nichts unter `/kc/` (ADR-50).
 *
 * `ModuleApiGroups` und `ContractSplit` sorgen dafuer beim Erzeugen. Dieser Test prueft das
 * Ergebnis, denn ein Betriebs-, Mock- oder Keycloak-Endpunkt, der einmal unter `api/published/`
 * steht, laesst sich nur noch als Bruch wieder entfernen.
 */
class ContractScopeTest : BehaviorSpec({

    given("api/openapi.yaml") {
        then("every path lies under $API_V1") {
            val paths = pathsOf(CONTRACT)
            paths.shouldNotBeEmpty()
            paths.filterNot { it.startsWith("$API_V1/") }.shouldBeEmpty()
        }
    }

    listOf(PARTS, PUBLISHED).forEach { dir ->
        given(PARTS.parent.relativize(dir).toString()) {
            then("every part's path lies under $API_V1, none under /kc") {
                val files = Files.walk(dir).use { walk -> walk.filter { it.toString().endsWith(".yaml") }.toList() }
                files.shouldNotBeEmpty()
                files.forEach { file ->
                    val paths = pathsOf(file)
                    paths.filterNot { it.startsWith("$API_V1/") && !it.startsWith("$API_V1/kc/") }.shouldBeEmpty()
                }
            }
        }
    }
}) {
    private companion object {
        private val CONTRACT: Path = Path.of("api", "openapi.yaml").toAbsolutePath()
        private val PARTS: Path = Path.of("api", "contract").toAbsolutePath()
        private val PUBLISHED: Path = Path.of("api", "published").toAbsolutePath()

        @Suppress("UNCHECKED_CAST")
        fun pathsOf(file: Path): Set<String> =
            ((Yaml().load<Map<String, Any?>>(Files.readString(file))["paths"] as? Map<String, Any?>).orEmpty()).keys
    }
}
