package com.example.identity.core.orchestrator

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.MDC
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.util.UUID

/**
 * Puts a request id and the channel or tool session the URL names into the MDC, so one user's
 * journey can be followed across log lines. Only ids already in the URL, nothing personal. First
 * in the chain, so the security and readiness filters log with them too.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class LoggingContextFilter : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val target = request.queryString?.let { "${request.requestURI}?$it" } ?: request.requestURI
        val keys = contextOf(target) + (REQUEST_ID to UUID.randomUUID().toString().substring(0, 8))
        keys.forEach { (key, value) -> MDC.put(key, value) }
        try {
            filterChain.doFilter(request, response)
        } finally {
            keys.keys.forEach(MDC::remove)
        }
    }

    companion object {
        const val REQUEST_ID = "requestId"
        const val CHANNEL_SESSION_ID = "channelSessionId"
        const val TOOL_SESSION_ID = "toolSessionId"

        private val UUID_PATTERN = "[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
        private val CHANNEL = Regex("(?:/channels/|[?&]channel=)($UUID_PATTERN)")
        private val TOOL = Regex("/tools/api/[^/?]+/v[0-9]+/($UUID_PATTERN)")

        /** The session ids [uri] (path and query) names - a channel, a tool session, or both, or none. */
        fun contextOf(uri: String): Map<String, String> = buildMap {
            CHANNEL.find(uri)?.let { put(CHANNEL_SESSION_ID, it.groupValues[1]) }
            TOOL.find(uri)?.let { put(TOOL_SESSION_ID, it.groupValues[1]) }
        }
    }
}
