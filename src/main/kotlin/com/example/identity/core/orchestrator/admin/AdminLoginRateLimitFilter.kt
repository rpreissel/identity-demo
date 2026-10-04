package com.example.identity.core.orchestrator.admin

import com.example.identity.core.orchestrator.session.RateLimitCounter
import com.example.identity.core.orchestrator.session.RateLimitScope
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.security.core.AuthenticationException
import org.springframework.security.web.authentication.www.BasicAuthenticationConverter
import org.springframework.web.filter.OncePerRequestFilter
import java.time.Duration

/**
 * The operator login is guessed like any other password: after [MAX_ATTEMPTS] attempts within
 * [WINDOW] for one user name, requests under that name are refused with 429 - the right password
 * included, so the lock reveals nothing about it. Each attempt is booked in one `UPDATE` before the
 * password is checked, so parallel guesses cannot pass the lock together (docs/07-betrieb.md #4). The
 * user name is read by Spring's own parser, the one that authenticates, so no spelling of the
 * header escapes the count. A success resets the counter. Runs before HTTP Basic in the admin chain.
 */
class AdminLoginRateLimitFilter(private val counter: RateLimitCounter) : OncePerRequestFilter() {

    private val basicParser = BasicAuthenticationConverter()

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val user = basicUserOf(request)
        if (user == null) {
            chain.doFilter(request, response)
            return
        }
        if (!counter.recordWindowedAttempt(RateLimitScope.ADMIN, user, MAX_ATTEMPTS, WINDOW)) {
            response.status = HttpStatus.TOO_MANY_REQUESTS.value()
            return
        }
        chain.doFilter(request, response)
        if (response.status in 200..399) counter.reset(RateLimitScope.ADMIN, user)
    }

    /** Null when Spring finds no Basic credentials; a header it cannot parse never authenticates either. */
    private fun basicUserOf(request: HttpServletRequest): String? =
        try {
            basicParser.convert(request)?.name?.lowercase()?.take(128)?.ifEmpty { null }
        } catch (_: AuthenticationException) {
            null
        }

    private companion object {
        const val MAX_ATTEMPTS = 5
        val WINDOW: Duration = Duration.ofMinutes(15)
    }
}
