package com.example.identity.core.orchestrator.dpop

import jakarta.servlet.http.HttpServletRequest

/**
 * Reconstructs the URL the client called, in the `htu` shape proofs are checked against (scheme,
 * host, non-default port, path). Behind a TLS-terminating proxy this needs
 * `server.forward-headers-strategy`, or every proof fails the `htu` check. That is safe only
 * where a trusted proxy overwrites `X-Forwarded-*`.
 */
fun buildRequestUrl(request: HttpServletRequest): String = buildString {
    append(request.scheme).append("://").append(request.serverName)
    val port = request.serverPort
    val scheme = request.scheme
    if ((scheme == "http" && port != 80) || (scheme == "https" && port != 443)) {
        append(":").append(port)
    }
    append(request.requestURI)
}

/**
 * Whether a proof's `htu` names the request it came with (RFC 9449 section 4.3): query and fragment
 * ignored, scheme and host case-insensitive, default port as absent, the path compared exactly.
 * An `htu` that is no absolute http(s) URL matches nothing.
 */
fun htuMatches(htu: String, requestUrl: String): Boolean {
    val claimed = targetOf(htu) ?: return false
    val actual = targetOf(requestUrl) ?: return false
    return claimed == actual
}

private data class Target(val scheme: String, val host: String, val port: Int, val path: String)

private fun targetOf(url: String): Target? {
    val uri = runCatching { java.net.URI(url.substringBefore('#').substringBefore('?')) }.getOrNull() ?: return null
    val scheme = uri.scheme?.lowercase()?.takeIf { it == "http" || it == "https" } ?: return null
    val host = uri.host?.lowercase() ?: return null
    val port = if (uri.port == -1) (if (scheme == "https") 443 else 80) else uri.port
    return Target(scheme, host, port, uri.rawPath.orEmpty().ifEmpty { "/" })
}
