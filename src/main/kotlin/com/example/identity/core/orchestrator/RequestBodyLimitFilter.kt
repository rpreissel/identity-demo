package com.example.identity.core.orchestrator

import jakarta.servlet.FilterChain
import jakarta.servlet.ReadListener
import jakarta.servlet.ServletInputStream
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletRequestWrapper
import jakarta.servlet.http.HttpServletResponse
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.Ordered
import org.springframework.core.annotation.Order
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.io.IOException

/**
 * Caps every request body before anything reads it. Spring parses a body before a handler can check
 * the caller's proof or assertion, and DPoP keys cost nothing; without a cap one request could fill
 * the heap. A declared length over the cap is `413`; a body without a declared length stops at the
 * cap and is unreadable (`400`).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class RequestBodyLimitFilter(
    @Value("\${identity.http.max-request-body-bytes:65536}") private val maxBytes: Long,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        if (request.contentLengthLong > maxBytes) {
            response.status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE
            return
        }
        chain.doFilter(LimitedRequest(request, maxBytes), response)
    }

    private class LimitedRequest(request: HttpServletRequest, private val maxBytes: Long) : HttpServletRequestWrapper(request) {
        private val limited by lazy { LimitedInputStream(request.inputStream, maxBytes) }

        override fun getInputStream(): ServletInputStream = limited
    }

    private class LimitedInputStream(private val delegate: ServletInputStream, private val maxBytes: Long) : ServletInputStream() {
        private var read = 0L

        override fun read(): Int = delegate.read().also { if (it >= 0) count(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len).also { if (it > 0) count(it.toLong()) }

        private fun count(n: Long) {
            read += n
            if (read > maxBytes) throw IOException("Request body exceeds $maxBytes bytes")
        }

        override fun isFinished(): Boolean = delegate.isFinished
        override fun isReady(): Boolean = delegate.isReady
        override fun setReadListener(listener: ReadListener) = delegate.setReadListener(listener)
    }
}
