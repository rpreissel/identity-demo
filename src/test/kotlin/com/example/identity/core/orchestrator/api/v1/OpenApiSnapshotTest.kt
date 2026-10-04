package com.example.identity.core.orchestrator.api.v1

import com.example.identity.contract.tool_api.ToolModule
import com.example.identity.core.orchestrator.SharedSpringContext
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.web.client.RestTemplate
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.Yaml
import tools.jackson.databind.json.JsonMapper
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.Callable
import java.util.concurrent.Executors

/**
 * Keeps `api/openapi.yaml`, the one written-down API contract, in step with the running code. A
 * changed controller or DTO fails here, in the diff of a checked-in file, not silently in a client.
 * The frontend types and the extension's Java models are generated from the same snapshot; the
 * versioned parts under `api/contract/` are split from it here as well ([ContractSplit]).
 *
 * To accept an intended change: `./gradlew updateOpenApiSnapshot`, then
 * `./gradlew generateFrontendApiTypes`, and review both diffs (docs/adr/ADR-026).
 */
class OpenApiSnapshotTest : SharedSpringContext() {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var groupedApis: List<GroupedOpenApi>

    @Autowired
    private lateinit var toolModules: List<ToolModule>

    init {
        given("the running application's springdoc endpoint") {
            then("every group matches its checked-in snapshot under api/") {
                val update = System.getProperty(UPDATE_PROPERTY) == "true"
                val outdated = mutableListOf<String>()
                val specs = fetchAll(groups())
                val shared = sharedSchemas(specs)

                val snapshots = specs.map { (group, spec) ->
                    snapshotFor(group) to render(if (group == CONTRACT_GROUP) spec else referenceShared(spec, shared))
                } + versionedParts(specs.getValue(CONTRACT_GROUP))

                snapshots.forEach { (file, live) ->
                    if (update) {
                        Files.createDirectories(file.parent)
                        Files.writeString(file, live)
                        return@forEach
                    }
                    val stored = if (Files.exists(file)) Files.readString(file) else ""
                    if (live != stored) outdated += SNAPSHOT.parent.relativize(file).toString()
                }
                // A removed tool leaves its file behind; it must go with the tool.
                val written = snapshots.map { it.first }.toSet()
                staleToolFiles(written).forEach { file ->
                    if (update) Files.delete(file) else outdated += "${SNAPSHOT.parent.relativize(file)} (Tool gibt es nicht mehr)"
                }

                if (update) {
                    println("OpenAPI-Snapshots aktualisiert unter ${SNAPSHOT.parent}")
                    return@then
                }
                if (outdated.isNotEmpty()) {
                    throw AssertionError(
                        """
                        Der API-Vertrag hat sich geaendert, diese Snapshots sind nicht nachgezogen:
                        ${outdated.joinToString("\n                        ")}

                        Beabsichtigt? Dann:
                          ./gradlew updateOpenApiSnapshot
                          ./gradlew generateFrontendApiTypes
                        und die Diffs pruefen - keycloak-extension erzeugt ihre Modelle beim Build aus demselben Vertrag.
                        """.trimIndent()
                    )
                }
            }

            then("no endpoint claims the caller supplies its own bindingKeyRef") {
                // @BindingKey comes from the DPoP proof or the peer-auth assertion, not from the URL.
                // springdoc does not see argument resolvers and would list it as a query parameter;
                // BindingKeyOpenApiConfig hides it, and this catches a new controller that leaks it.
                val spec = render(fetch(CONTRACT_GROUP))
                if (spec.contains("name: bindingKeyRef")) {
                    throw AssertionError(
                        "bindingKeyRef steht wieder als Parameter in der Spec. Er kommt aus dem " +
                            "DPoP-Header, nicht aus der URL - siehe BindingKeyOpenApiConfig."
                    )
                }
                // The endpoints must still say how a caller authenticates at all.
                if (!spec.contains(BindingKeyOpenApiConfig.PEER_AUTH_SCHEME)) {
                    throw AssertionError("Kein Endpunkt deklariert mehr die Peer-Auth-Alternative.")
                }
            }
        }
    }

    /**
     * The envelope and one file per tool, the units `checkPublishedApiCompatibility` compares
     * against their published state (ADR-50). Generated here from the same contract as above.
     */
    private fun versionedParts(contract: Map<*, *>): List<Pair<Path, String>> {
        val tools = toolModules.flatMap { module -> module.tools.map { it.toolId.value to module.stepData.keys } }.toMap()
        val split = ContractSplit(contract, tools)
        return listOf(CONTRACT_PARTS.resolve("envelope.yaml") to render(split.envelope())) +
            tools.keys.sorted().map { toolId -> CONTRACT_PARTS.resolve("tools").resolve("$toolId.yaml") to render(split.tool(toolId)) }
    }

    private fun staleToolFiles(written: Set<Path>): List<Path> {
        val dir = CONTRACT_PARTS.resolve("tools")
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.list(dir).use { files -> files.filter { it !in written }.sorted().toList() }
    }

    /** The registered groups, read from the beans, so this test knows no modules (`ModuleApiGroups`). */
    private fun groups(): List<String> = groupedApis.map { it.group }.sorted()

    /**
     * The combined group is the contract the code generators read. Per-module files sit in
     * `api/modules/`, so a change to one module's endpoints is a diff in one small file.
     */
    private fun snapshotFor(group: String) =
        if (group == CONTRACT_GROUP) SNAPSHOT else SNAPSHOT.parent.resolve("modules").resolve("$group.yaml")

    /**
     * All groups at once. springdoc builds each group's spec on first request, about half a second
     * each; it locks per group, and the converters here keep no state, so the groups build side by side.
     */
    private fun fetchAll(groups: List<String>): Map<String, Map<*, *>> =
        Executors.newFixedThreadPool(groups.size.coerceIn(1, Runtime.getRuntime().availableProcessors())).use { pool ->
            groups.map { group -> group to pool.submit(Callable { fetch(group) }) }
                .associate { (group, spec) -> group to spec.get() }
        }

    /** The group's spec minus `servers`, which holds this run's random port, not the contract. */
    private fun fetch(group: String): Map<*, *> {
        val raw = RestTemplate().getForObject("http://localhost:$port/v3/api-docs/$group", String::class.java)
            ?: error("springdoc lieferte keine Spec fuer die Gruppe '$group'")
        return JsonMapper().readValue(raw, Map::class.java).toMutableMap().apply { remove("servers") }
    }

    private fun schemasOf(spec: Map<*, *>): Map<*, *> =
        ((spec["components"] as? Map<*, *>)?.get("schemas") as? Map<*, *>) ?: emptyMap<String, Any?>()

    /**
     * The schemas that more than one module uses and the contract holds. A module file references
     * them instead of repeating the shared envelope. A schema the contract lacks stays inline.
     *
     * `api/openapi.yaml` stays a single file: with external `$ref`s, swagger-parser inlines them
     * under OpenAPI 3.1 and both generators lose the model names and the discriminator mapping.
     */
    private fun sharedSchemas(specs: Map<String, Map<*, *>>): Set<String> {
        val contract = schemasOf(specs.getValue(CONTRACT_GROUP)).keys.map { it.toString() }.toSet()
        return specs.filterKeys { it != CONTRACT_GROUP }.values
            .flatMap { spec -> schemasOf(spec).keys.map { it.toString() } }
            .groupingBy { it }.eachCount()
            .filter { (name, users) -> users > 1 && name in contract }
            .keys
    }

    /**
     * Drops the [shared] schemas from a module's spec and points its references at `../openapi.yaml`.
     * For `StepData` that is the full union, not only the shapes this module produces.
     */
    private fun referenceShared(spec: Map<*, *>, shared: Set<String>): Map<*, *> {
        val kept = schemasOf(spec).filterKeys { it.toString() !in shared }
        val components = (spec["components"] as? Map<*, *>)?.toMutableMap()?.apply {
            if (kept.isEmpty()) remove("schemas") else put("schemas", kept)
        }
        val trimmed = spec.toMutableMap().apply { if (components != null) put("components", components) }
        return rewriteRefs(trimmed, shared) as Map<*, *>
    }

    /** `$ref`s and discriminator mappings alike: both are plain strings naming a local schema. */
    private fun rewriteRefs(value: Any?, shared: Set<String>): Any? = when (value) {
        is Map<*, *> -> value.mapValues { rewriteRefs(it.value, shared) }
        is List<*> -> value.map { rewriteRefs(it, shared) }
        is String ->
            if (value.startsWith(LOCAL_SCHEMA_REF) && value.removePrefix(LOCAL_SCHEMA_REF) in shared) {
                CONTRACT_SCHEMA_REF + value.removePrefix(LOCAL_SCHEMA_REF)
            } else {
                value
            }
        else -> value
    }

    /**
     * YAML rather than JSON, because people read this file in diffs. Keys are sorted recursively:
     * springdoc builds its spec from hash-ordered maps, so unsorted output would differ between two
     * runs of unchanged code.
     */
    private fun render(spec: Map<*, *>): String {
        val options = DumperOptions().apply {
            defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
            isPrettyFlow = true
            width = 120
            indent = 2
        }
        return Yaml(options).dump(sortDeeply(spec)).trimEnd() + "\n"
    }

    /** Sorted maps all the way down; lists keep their order, which is meaningful in OpenAPI. */
    private fun sortDeeply(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.entries
            .sortedBy { it.key.toString() }
            .associateTo(LinkedHashMap()) { it.key.toString() to sortDeeply(it.value) }
        is List<*> -> value.map { sortDeeply(it) }
        else -> value
    }

    companion object {
        private const val UPDATE_PROPERTY = "openapi.snapshot.update"
        private const val CONTRACT_GROUP = com.example.identity.core.orchestrator.api.v1.CONTRACT_GROUP
        private const val LOCAL_SCHEMA_REF = "#/components/schemas/"
        private const val CONTRACT_SCHEMA_REF = "../openapi.yaml#/components/schemas/"

        /** Under `api/`, not `docs/`: it is generated, and docs/ stays hand-written (AGENTS.md). */
        private val SNAPSHOT: Path = Path.of("api", "openapi.yaml").toAbsolutePath()

        /** The versioned parts of the contract; their frozen copies live under `api/published/`. */
        private val CONTRACT_PARTS: Path = SNAPSHOT.parent.resolve("contract")
    }
}
