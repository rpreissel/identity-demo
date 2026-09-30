package com.example.identity.contract.tool_api.ratelimit

import com.example.identity.contract.tool_api.ModuleId

import java.time.Duration

/**
 * The counting mechanism the orchestrator lends to tool modules (ADR-44): one atomic count per
 * key in a rolling window. Keys are stored only as a keyed hash. Modules reach it through
 * [RateLimit], which fixes the namespace.
 */
interface RateLimits {
    /** Counts one attempt for [key]; `false` once this attempt exceeds the budget. */
    fun tryAttempt(budget: RateLimit, key: String): Boolean

    /** Starts the budget for [key] over. */
    fun reset(budget: RateLimit, key: String)
}

/**
 * One budget of a tool module: the module picks limits, key and when to reset (ADR-44). The
 * namespace is the declaring module and class, so a module can only count in its own space; a
 * subclass in another module would need a dependency Spring Modulith forbids.
 */
abstract class RateLimit(
    private val budgets: RateLimits,
    val maxPerWindow: Int,
    val window: Duration,
) {
    val namespace: String = namespaceOf(javaClass)

    protected fun tryAttempt(key: String): Boolean = budgets.tryAttempt(this, key)

    protected fun reset(key: String) = budgets.reset(this, key)

    companion object {
        /** `<module id>.<class>`, e.g. `auth_sms.SmsSendLimit`. */
        fun namespaceOf(type: Class<*>): String = "${ModuleId.of(type).id}.${type.simpleName}"
    }
}
