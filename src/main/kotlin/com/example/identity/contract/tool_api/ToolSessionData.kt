package com.example.identity.contract.tool_api

import com.example.identity.contract.tool_api.ids.ToolSessionId
import kotlin.reflect.KClass

/**
 * The working data of a tool session, kept by the orchestrator next to the session itself
 * (docs/03-tool-architektur.md #2, ADR-49). A tool keeps its state as a plain data class and
 * replaces it whole: [save] after every change, nothing is tracked behind its back. The data
 * lives and ends with its tool session; a module brings no table and no cleanup of its own.
 *
 * Like [com.example.identity.contract.tool_api.ratelimit.RateLimits], the namespace is the state's
 * module and class: a module reads only the states it declares itself.
 */
interface ToolSessionData {
    /** The state of [toolSessionId], or `null` before the first [save]. A state of another type is a contract error. */
    fun <S : Any> load(toolSessionId: ToolSessionId, type: KClass<S>): S?

    /** Replaces the state of [toolSessionId]. The session must exist and keep one state type. */
    fun save(toolSessionId: ToolSessionId, state: Any)
}

/** [ToolSessionData.load] with the type spelled once: `sessions.load<AuthSmsToolSession>(id)`. */
inline fun <reified S : Any> ToolSessionData.load(toolSessionId: ToolSessionId): S? = load(toolSessionId, S::class)

/** The state of a session this tool has started, which must therefore exist. */
inline fun <reified S : Any> ToolSessionData.require(toolSessionId: ToolSessionId): S =
    checkNotNull(load(toolSessionId, S::class)) { "No ${S::class.simpleName} for tool session $toolSessionId" }
