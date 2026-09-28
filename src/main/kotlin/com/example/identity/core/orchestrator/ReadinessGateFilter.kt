package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.kc.CLIENT_JWKS_PATH
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Antwortet mit 503, solange [ReadinessState.isReady] false ist. Einzige Ausnahme: das Client-JWKS
 * ([CLIENT_JWKS_PATH]), gegen das Keycloak die Anmeldung der Migration selbst prueft. Es liefert
 * nur oeffentliches Schluesselmaterial, das schon beim Start feststeht.
 */
@Component
class ReadinessGateFilter(private val readinessState: ReadinessState) : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.requestURI.startsWith("$CLIENT_JWKS_PATH/")

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        if (!readinessState.isReady) {
            response.status = HttpServletResponse.SC_SERVICE_UNAVAILABLE
            response.contentType = "application/json"
            response.writer.write(
                """{"error":"starting_up","error_description":"Orchestrator startet noch (Keycloak-Migrationen laufen)"}"""
            )
            return
        }
        filterChain.doFilter(request, response)
    }
}
