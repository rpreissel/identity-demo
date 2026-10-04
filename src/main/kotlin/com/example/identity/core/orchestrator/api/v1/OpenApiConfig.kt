package com.example.identity.core.orchestrator.api.v1

import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.security.SecurityScheme
import io.swagger.v3.oas.models.tags.Tag
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/** Exposed at /v3/api-docs and /swagger-ui/index.html (docs/05-api.md). */
@Configuration
class OpenApiConfig {

    @Bean
    fun identityDemoOpenApi(): OpenAPI = OpenAPI()
        /**
         * One description per tag, declared here. A method's tools live in several controllers;
         * differing descriptions on the same tag make the OpenAPI Generator reject the spec. The
         * controllers carry `@Tag(name = ...)` without description.
         */
        .tags(
            listOf(
                tag("Tool: SMS", "SMS-TAN: Einrichtung als Faktor, Anmeldung und Login ohne DPoP"),
                tag("Tool: E-Mail", "E-Mail: Bestaetigung der Adresse, Anmeldung und Login ohne DPoP"),
                tag("Tool: Passwort", "Passwort: Einrichtung als Wissensfaktor, Anmeldung und Login ohne DPoP"),
                tag("Tool: Gerät", "Geraetebindung: Einrichtung und Anmeldung ueber den Geraeteschluessel"),
                tag("Tool: QR-Login", "QR-Pairing: Freigabe eines Web-Kanals aus einer bereits angemeldeten App"),
                tag("Tool: KOBIL", "Gerätebindung über den externen Dienstleister KOBIL")
            )
        )
        .info(
            Info()
                .title("Identity-Demo Orchestrator API")
                .version("v1")
                .description(
                    "App-facing (orchestrator-first) API for the DPoP-bound registration and login demo. " +
                        "See docs/05-api.md for the full contract; the Keycloak-facing Keycloak facade is out of " +
                        "scope for this build (docs/08-projektrahmen.md)."
                )
        )
        /**
         * The two ways a caller proves who it is ([DpopBindingKeyResolver]). Which endpoints
         * accept them is stated per operation by [BindingKeyOpenApiConfig].
         */
        .components(
            Components()
                .addSecuritySchemes(
                    BindingKeyOpenApiConfig.DPOP_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.APIKEY)
                        .`in`(SecurityScheme.In.HEADER)
                        .name("DPoP")
                        .description(
                            "DPoP proof JWT, one per request, bound to method and URL " +
                                "(docs/09-dpop.md). The App channel's proof; the binding key is " +
                                "derived from it and is never supplied by the caller."
                        )
                )
                .addSecuritySchemes(
                    BindingKeyOpenApiConfig.PEER_AUTH_SCHEME,
                    SecurityScheme()
                        .type(SecurityScheme.Type.HTTP)
                        .scheme("bearer")
                        .bearerFormat("JWT")
                        .description(
                            "Signed peer-auth assertion (docs/05-api.md Abschnitt 3b, ADR-7). The " +
                                "Web channel's proof: Keycloak calls server-to-server, and the " +
                                "channel binding comes out of the assertion."
                        )
                )
        )

    private fun tag(name: String, description: String) = Tag().name(name).description(description)
}
