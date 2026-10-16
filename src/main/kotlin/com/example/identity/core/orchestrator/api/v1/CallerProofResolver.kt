package com.example.identity.core.orchestrator.api.v1

import com.example.identity.core.orchestrator.keycloak.peerAuthBodySha256
import com.example.identity.core.orchestrator.keycloak.peerAuthTarget
import com.example.identity.core.orchestrator.dpop.DpopFailure
import com.example.identity.core.orchestrator.dpop.DpopValidationException
import com.example.identity.core.orchestrator.dpop.DpopValidator
import com.example.identity.core.orchestrator.dpop.JwkThumbprintService

import com.example.identity.core.orchestrator.keycloak.PeerAuthValidator
import com.example.identity.contract.tool_api.BindingKey
import com.example.identity.contract.tool_api.CallerProof
import com.example.identity.core.orchestrator.keycloak.asChannelCaller
import com.example.identity.core.orchestrator.dpop.buildRequestUrl
import jakarta.servlet.http.HttpServletRequest
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

/**
 * Resolves any `@BindingKey` controller parameter before the method body runs, so proof validation
 * lives in one place (docs/04-orchestrierung.md #8). The tool endpoints serve both channels
 * (docs/05-api.md Abschnitt 3b): a DPoP proof yields [CallerProof.DeviceKey], a Keycloak peer-auth
 * assertion [CallerProof.KeycloakFlow]. A parameter typed [CallerProof.DeviceKey] accepts DPoP only.
 * The comparison with the channel happens later ([com.example.identity.core.orchestrator.channel.ChannelAccessGuard]).
 */
@Component
class CallerProofResolver(
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
    ): CallerProof {
        val request = checkNotNull(webRequest.getNativeRequest(HttpServletRequest::class.java)) {
            "@BindingKey resolution requires a servlet request"
        }
        return callerOf(request, deviceOnly = parameter.parameterType == CallerProof.DeviceKey::class.java)
    }

    /** The caller proven by the request's DPoP proof or peer-auth assertion. */
    fun callerOf(request: HttpServletRequest, deviceOnly: Boolean = false): CallerProof {
        val dpopProof = request.getHeader("DPoP")
        if (dpopProof != null) {
            val proof = dpopValidator.validate(dpopProof, request.method, buildRequestUrl(request))
            return CallerProof.DeviceKey(jwkThumbprintService.computeThumbprint(proof.publicKey))
        }
        if (deviceOnly) throw DpopValidationException(DpopFailure.MISSING)

        val authorization = request.getHeader("Authorization")
            ?: throw DpopValidationException(DpopFailure.MISSING)
        val token = if (authorization.startsWith("Bearer ", ignoreCase = true)) {
            authorization.substring(7).trim()
        } else {
            authorization.trim()
        }
        return peerAuthValidator.validate(token, request.method, peerAuthTarget(request), peerAuthBodySha256(request)).asChannelCaller()
    }
}
