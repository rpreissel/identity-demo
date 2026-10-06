package com.example.identity.core.orchestrator.session

import com.example.identity.contract.tool_api.ToolSessionData
import com.example.identity.contract.tool_api.ids.ToolSessionId
import com.example.identity.core.account.DataKeyWrapping
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import kotlin.reflect.KClass

/**
 * Keeps a tool's working data in its [ToolSession] row, as JSON next to the lifecycle data (ADR-49),
 * sealed under the day's data key (ADR-53) and bound to the session and its type. It ends with the
 * row, so no module needs a table or a cleanup of its own. The row's `@Version` also covers the
 * data: two writers of one session cannot silently overwrite each other.
 */
@Service
@Transactional
class ToolSessionDataService(
    private val toolSessionRepository: ToolSessionRepository,
    private val codec: ToolSessionDataCodec,
    private val keys: RetentionClassKeys,
    private val wrapping: DataKeyWrapping,
) : ToolSessionData {

    /** Regardless of the session's status: a GET answers a superseded session too. */
    @Transactional(readOnly = true)
    override fun <S : Any> load(toolSessionId: ToolSessionId, type: KClass<S>): S? {
        val session = toolSessionRepository.findByToolSessionId(toolSessionId) ?: return null
        val stored = session.dataType ?: return null
        val expected = codec.typeName(type)
        check(stored == expected) { "Tool session $toolSessionId holds $stored, not $expected" }
        val sealed = checkNotNull(session.data) { "Tool session $toolSessionId names $stored but holds no data" }
        val key = checkNotNull(session.dataKeyId?.let(keys::keyOf)) { "Tool session $toolSessionId: its data key ${session.dataKeyId} is retired" }
        return codec.read(String(wrapping.open(key, aad(toolSessionId, stored), sealed)), type)
    }

    override fun save(toolSessionId: ToolSessionId, state: Any) {
        val session = checkNotNull(toolSessionRepository.findByToolSessionId(toolSessionId)) { "Unknown tool session $toolSessionId" }
        val type = codec.typeName(state::class)
        session.dataType?.let { check(it == type) { "Tool session $toolSessionId holds $it, cannot take $type" } }
        session.dataType = type
        val key = keys.currentToolSessionKey()
        session.dataKeyId = key.keyId
        session.data = wrapping.seal(key.key, aad(toolSessionId, type), codec.write(state).toByteArray())
    }

    private fun aad(toolSessionId: ToolSessionId, type: String) = "tool-session:${toolSessionId.value}:$type".toByteArray()
}
