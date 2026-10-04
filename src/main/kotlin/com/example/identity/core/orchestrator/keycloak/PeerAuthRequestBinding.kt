package com.example.identity.core.orchestrator.keycloak

import com.example.identity.core.orchestrator.dpop.buildRequestUrl
import com.nimbusds.jose.util.Base64URL
import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.ByteArrayInputStream
import java.security.MessageDigest

/**
 * The peer-auth assertion binds the whole request, body and query included (ADR-7): whoever sits on
 * the hop between Keycloak and the orchestrator must not swap a body under a valid assertion. This
 * filter reads the body of every request carrying a Bearer token once, before anything parses it,
 * hashes it for [PeerAuthValidator] and hands the same bytes on. It runs after the body cap.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
class PeerAuthBodyCaptureFilter : OncePerRequestFilter() {

    override fun shouldNotFilter(request: HttpServletRequest): Boolean =
        request.getHeader("Authorization")?.startsWith("Bearer ", ignoreCase = true) != true

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        val body = request.inputStream.readAllBytes()
        request.setAttribute(BODY_SHA256_ATTRIBUTE, sha256(body))
        chain.doFilter(ReplayedBody(request, body), response)
    }

    private class ReplayedBody(request: HttpServletRequest, private val body: ByteArray) : HttpServletRequestWrapper(request) {
        override fun getInputStream(): ServletInputStream {
            val bytes = ByteArrayInputStream(body)
            return object : ServletInputStream() {
                override fun read(): Int = bytes.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int = bytes.read(b, off, len)
                override fun isFinished(): Boolean = bytes.available() == 0
                override fun isReady(): Boolean = true
                override fun setReadListener(listener: ReadListener) = throw UnsupportedOperationException()
            }
        }
    }

    companion object {
        const val BODY_SHA256_ATTRIBUTE = "identity.peerAuth.bodySha256"

        /** Base64url SHA-256, the same form as the response signature's `body_sha256`. */
        fun sha256(body: ByteArray): String = Base64URL.encode(MessageDigest.getInstance("SHA-256").digest(body)).toString()
    }
}

/** The address a peer-auth assertion's `htu` names: scheme, host, port, path and the raw query. */
fun peerAuthTarget(request: HttpServletRequest): String =
    buildRequestUrl(request) + (request.queryString?.let { "?$it" } ?: "")

/**
 * The hash of the body [PeerAuthBodyCaptureFilter] read. Missing only when the filter did not run,
 * and then nothing can show the body is the signed one.
 */
fun peerAuthBodySha256(request: HttpServletRequest): String =
    request.getAttribute(PeerAuthBodyCaptureFilter.BODY_SHA256_ATTRIBUTE) as? String
        ?: throw PeerAuthValidationException("Request body was not captured for the peer-auth check")
