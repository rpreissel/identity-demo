package com.example.identity.core.orchestrator.api.v1

import com.example.identity.contract.tool_api.envelope.API_V1

/**
 * Splits the app contract into the parts that are versioned separately (ADR-50):
 *
 * - the envelope: everything every client relies on. A break needs a new API version.
 * - one file per tool: its endpoints, their schemas and the step shapes its module declares. A
 *   client only ever meets a tool it named in `availableTools`, so a break reaches only those clients.
 *
 * `/kc/...` is in neither: only the Keycloak extension calls it, and that ships with the server.
 * The peer-auth alternative on shared endpoints goes for the same reason.
 *
 * Each file is self-contained (openapi-diff compares split files unreliably). In a tool file the
 * envelope's schemas stand as open placeholders, so an envelope change is reported once, in the
 * envelope. Only the properties leading to `StepData` remain, and there `StepData` lists the
 * tool's own shapes: openapi-diff compares what the paths reach, not loose components.
 */
class ContractSplit(
    private val contract: Map<*, *>,
    /** toolId -> the `kind`s its module declares. */
    private val tools: Map<String, Set<String>>,
) {

    private val schemas: Map<String, Any?> =
        ((contract["components"] as? Map<*, *>)?.get("schemas") as? Map<*, *>).orEmpty()
            .mapKeys { it.key.toString() }

    private val mapping: Map<String, String> =
        (((schemas[STEP_DATA] as? Map<*, *>)?.get("discriminator") as? Map<*, *>)?.get("mapping") as? Map<*, *>).orEmpty()
            .entries.associate { it.key.toString() to it.value.toString().removePrefix(LOCAL_REF) }

    private val envelopeKinds: Set<String> = mapping.keys - tools.values.flatten().toSet()

    private val paths: Map<String, Any?> = (contract["paths"] as Map<*, *>)
        .mapKeys { it.key.toString() }
        .filterKeys { !it.startsWith("$API_V1/kc/") }

    private val envelopePaths = paths.filterKeys { toolOf(it) == null }

    /** Everything the envelope reaches; `StepData` counts only with the envelope's own shapes. */
    private val envelopeSchemas: Set<String> = reachable(refsIn(envelopePaths), envelopeKinds, emptySet())

    fun envelope(): Map<*, *> =
        document(envelopePaths, envelopeSchemas.associateWith { schemaFor(it, envelopeKinds) })

    fun tool(toolId: String): Map<*, *> {
        val ownPaths = paths.filterKeys { toolOf(it) == toolId }
        val kinds = tools.getValue(toolId)
        val roots = refsIn(ownPaths) + kinds.mapNotNull { mapping[it] }
        val own = reachable(roots, emptySet(), envelopeSchemas)
        val usedEnvelope = (refsIn(ownPaths) + own.flatMap { refsIn(schemas[it]) }) intersect envelopeSchemas
        val placeholders = (usedEnvelope + if (kinds.isEmpty()) emptySet() else usedEnvelope.flatMap { leadsToStepData(it) })
            .associateWith { placeholder(it, kinds) }
        return document(ownPaths, own.associateWith { schemas[it] } + placeholders)
    }

    /** The toolId a path belongs to, or null for the envelope. */
    fun toolOf(path: String): String? {
        val rest = path.removePrefix(API_V1)
        val id = (CHANNEL_TOOL.find(rest) ?: SESSION_TOOL.find(rest))?.groupValues?.get(1)
        return id?.takeIf { it in tools }
    }

    private fun document(paths: Map<String, Any?>, schemas: Map<String, Any?>): Map<*, *> {
        val operations = paths.mapValues { withoutPeerAuth(it.value) }
        val usedTags = refsOfTags(operations)
        val components = (contract["components"] as Map<*, *>).toMutableMap().apply {
            put("schemas", schemas.toSortedMap())
            (get("securitySchemes") as? Map<*, *>)?.let { schemes -> put("securitySchemes", schemes.filterKeys { it != PEER_AUTH }) }
        }
        return contract.toMutableMap().apply {
            put("paths", operations)
            put("components", components)
            (get("tags") as? List<*>)?.let { tags -> put("tags", tags.filter { (it as? Map<*, *>)?.get("name") in usedTags }) }
        }
    }

    /** Peer-auth entries out of every operation's `security`; DPoP stays. */
    private fun withoutPeerAuth(value: Any?): Any? = when (value) {
        is Map<*, *> -> value.mapValues { (key, nested) ->
            if (key == "security" && nested is List<*>) nested.filterNot { it is Map<*, *> && PEER_AUTH in it.keys }
            else withoutPeerAuth(nested)
        }
        is List<*> -> value.map { withoutPeerAuth(it) }
        else -> value
    }

    private fun refsOfTags(paths: Map<String, Any?>): Set<Any?> =
        paths.values.flatMap { (it as? Map<*, *>)?.values.orEmpty() }
            .flatMap { ((it as? Map<*, *>)?.get("tags") as? List<*>).orEmpty() }
            .toSet()

    /** Schema names reachable from [roots], with `StepData` narrowed to [kinds], not descending into [stop]. */
    private fun reachable(roots: Set<String>, kinds: Set<String>, stop: Set<String>): Set<String> {
        val seen = linkedSetOf<String>()
        val queue = ArrayDeque(roots)
        while (queue.isNotEmpty()) {
            val name = queue.removeFirst()
            if (name in stop || !seen.add(name)) continue
            queue += refsIn(schemaFor(name, kinds))
        }
        return seen
    }

    private fun schemaFor(name: String, kinds: Set<String>): Any? =
        if (name == STEP_DATA) stepDataWith(kinds) else schemas[name]

    private fun refsIn(value: Any?): Set<String> = when (value) {
        is Map<*, *> -> value.values.flatMap { refsIn(it) }.toSet()
        is List<*> -> value.flatMap { refsIn(it) }.toSet()
        is String -> if (value.startsWith(LOCAL_REF)) setOf(value.removePrefix(LOCAL_REF)) else emptySet()
        else -> emptySet()
    }

    /** [name] and the schemas between it and `StepData` along properties, or nothing if it does not lead there. */
    private fun leadsToStepData(name: String, seen: Set<String> = emptySet()): Set<String> {
        if (name == STEP_DATA) return setOf(STEP_DATA)
        if (name in seen) return emptySet()
        val below = refsIn(propertiesOf(name)).flatMap { leadsToStepData(it, seen + name) }.toSet()
        return if (below.isEmpty()) emptySet() else below + name
    }

    /** An envelope schema inside a tool file: an open object, keeping only the way to `StepData`. */
    private fun placeholder(name: String, kinds: Set<String>): Map<String, Any?> {
        if (name == STEP_DATA) return stepDataWith(kinds)
        val leading = if (kinds.isEmpty()) emptyMap()
        else propertiesOf(name).filterValues { property -> refsIn(property).any { leadsToStepData(it).isNotEmpty() } }
        return if (leading.isEmpty()) mapOf("type" to "object") else mapOf("type" to "object", "properties" to leading)
    }

    private fun propertiesOf(name: String): Map<*, *> =
        ((schemas[name] as? Map<*, *>)?.get("properties") as? Map<*, *>).orEmpty()

    /** `StepData` with only [kinds] in its union and mapping. */
    private fun stepDataWith(kinds: Set<String>): Map<String, Any?> {
        val base = schemas.getValue(STEP_DATA) as Map<*, *>
        val narrowed = mapping.filterKeys { it in kinds }.mapValues { LOCAL_REF + it.value }
        val discriminator = (base["discriminator"] as Map<*, *>).toMutableMap().apply { put("mapping", narrowed) }
        return base.entries.associate { it.key.toString() to it.value }.toMutableMap().apply {
            put("discriminator", discriminator)
            put("oneOf", narrowed.values.sorted().map { mapOf("\$ref" to it) })
        }
    }

    private companion object {
        const val STEP_DATA = "StepData"
        const val LOCAL_REF = "#/components/schemas/"
        const val PEER_AUTH = BindingKeyOpenApiConfig.PEER_AUTH_SCHEME
        val CHANNEL_TOOL = Regex("^/channels/\\{[^}]+}/tools/([^/]+)")
        val SESSION_TOOL = Regex("^/tools/\\{[^}]+}/([^/{]+)")
    }
}
