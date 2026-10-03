package com.example.identity.contract.tool_api

import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.orchestrator.session.ToolSessionDataCodec
import kotlin.reflect.KClass

/**
 * [ToolSessionData] for handler unit tests, without Spring or a database. Every state goes through
 * the real [ToolSessionDataCodec], so a state that does not survive JSON fails here already.
 */
class InMemoryToolSessionData : ToolSessionData {
    private val codec = ToolSessionDataCodec()
    private val rows = mutableMapOf<ToolSessionId, Pair<String, String>>()

    override fun <S : Any> load(toolSessionId: ToolSessionId, type: KClass<S>): S? {
        val (stored, json) = rows[toolSessionId] ?: return null
        check(stored == codec.typeName(type)) { "Tool session $toolSessionId holds $stored, not ${codec.typeName(type)}" }
        return codec.read(json, type)
    }

    override fun save(toolSessionId: ToolSessionId, state: Any) {
        val type = codec.typeName(state::class)
        rows[toolSessionId]?.let { (stored, _) -> check(stored == type) { "Tool session $toolSessionId holds $stored, cannot take $type" } }
        rows[toolSessionId] = type to codec.write(state)
    }

    /** The state as the tool last saved it, for assertions: `sessions.stored<AuthSmsToolSession>(id)`. */
    inline fun <reified S : Any> stored(toolSessionId: ToolSessionId): S = require(toolSessionId)
}
