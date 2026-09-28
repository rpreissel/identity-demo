package com.example.identity.core.orchestrator.api.v1

import com.example.identity.contract.tool_api.BindingKey
import io.swagger.v3.oas.models.security.SecurityRequirement
import org.springdoc.core.customizers.OperationCustomizer
import org.springdoc.core.utils.SpringDocUtils
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.HandlerMethod

/**
 * Describes how a caller proves who it is. `@BindingKey` parameters are filled by
 * [DpopBindingKeyResolver] from the DPoP proof or the peer-auth assertion, never from the URL.
 * springdoc would otherwise document them as a query parameter, telling clients to choose their own
 * key. Instead the parameter is hidden and the endpoints declare the security they really require.
 */
@Configuration
class BindingKeyOpenApiConfig {

    /**
     * Keeps the parameter from being built at all. The list is JVM-wide; registering twice (several
     * Spring contexts in a test run) is harmless, since it is a membership test.
     */
    init {
        SpringDocUtils.getConfig().addAnnotationsToIgnore(BindingKey::class.java)
    }

    /**
     * Declares the proofs an endpoint accepts: a `DPoP` header or a peer-auth assertion (two
     * entries mean OR), only the assertion for `keycloakOnly`. Per operation, because endpoints
     * without `@BindingKey` need neither.
     */
    @Bean
    fun bindingKeySecurityCustomizer(): OperationCustomizer = OperationCustomizer { operation, handlerMethod ->
        val bindingKey = bindingKeyOf(handlerMethod)
        if (bindingKey != null) {
            operation.security =
                if (bindingKey.keycloakOnly) listOf(SecurityRequirement().addList(PEER_AUTH_SCHEME))
                else listOf(SecurityRequirement().addList(DPOP_SCHEME), SecurityRequirement().addList(PEER_AUTH_SCHEME))
        }
        operation
    }

    private fun bindingKeyOf(handlerMethod: HandlerMethod): BindingKey? =
        handlerMethod.methodParameters.firstNotNullOfOrNull { it.getParameterAnnotation(BindingKey::class.java) }

    companion object {
        /** Declared in [OpenApiConfig]; the App channel's DPoP proof header. */
        const val DPOP_SCHEME = "dpop"

        /**
         * The Web channel's signed peer-auth assertion (docs/05-api.md Abschnitt 3, ADR-7). Must
         * match the `@SecurityRequirement(name = "kc-peer-auth")` on the kc controllers, or the
         * spec is invalid.
         */
        const val PEER_AUTH_SCHEME = "kc-peer-auth"
    }
}
