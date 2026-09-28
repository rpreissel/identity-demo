package com.example.identity.core.orchestrator.api.v1

import com.example.identity.core.orchestrator.channel.DeviceChannelAccessGuard
import com.example.identity.core.orchestrator.dpop.DpopProof
import com.example.identity.core.orchestrator.dpop.DpopFailure
import com.example.identity.core.orchestrator.dpop.DpopValidationException
import com.example.identity.core.orchestrator.dpop.DpopValidator
import com.example.identity.core.orchestrator.dpop.JwkThumbprintService

import com.example.identity.core.orchestrator.kc.PeerAuthValidationException
import com.example.identity.core.orchestrator.kc.PeerAuthValidator
import com.example.identity.contract.tool_api.BindingKey
import com.example.identity.core.orchestrator.dpop.buildRequestUrl
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * Resolves any `@BindingKey` controller parameter before the method body runs, so proof
 * validation lives in one place (docs/04-orchestrierung.md #5). The tool endpoints serve both
 * channels (docs/05-api.md Abschnitt 3): a DPoP proof yields the key thumbprint, a Keycloak
 * peer-auth assertion a `"kc:"`-prefixed anchor. The comparison with the channel happens later.
 */
@Component
class DpopBindingKeyResolver(
    private val dpopValidator: DpopValidator,
    private val jwkThumbprintService: JwkThumbprintService,
    private val peerAuthValidator: PeerAuthValidator
) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(BindingKey::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?
    ): String {
        val request = checkNotNull(webRequest.getNativeRequest(HttpServletRequest::class.java)) {
            "@BindingKey resolution requires a servlet request"
        }
        val keycloakOnly = parameter.getParameterAnnotation(BindingKey::class.java)?.keycloakOnly == true
        val dpopProof = request.getHeader("DPoP")
        if (dpopProof != null) {
            if (keycloakOnly) throw PeerAuthValidationException("Only Keycloak's peer-auth assertion is accepted here")
            val proof = dpopValidator.validate(dpopProof, request.method, buildRequestUrl(request))
            return jwkThumbprintService.computeThumbprint(proof.publicKey)
        }

        val authorization = request.getHeader("Authorization")
            ?: throw DpopValidationException(DpopFailure.MISSING)
        val token = if (authorization.startsWith("Bearer ", ignoreCase = true)) {
            authorization.substring(7).trim()
        } else {
            authorization.trim()
        }
        val assertion = peerAuthValidator.validate(token, request.method, buildRequestUrl(request))
        return "${DeviceChannelAccessGuard.KC_ANCHOR_PREFIX}${assertion.channelAnchor}"
    }
}
