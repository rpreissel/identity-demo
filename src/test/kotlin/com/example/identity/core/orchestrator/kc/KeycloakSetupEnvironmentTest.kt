package com.example.identity.core.orchestrator.kc

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.io.ClassPathResource
import org.springframework.mock.env.MockEnvironment

/**
 * [KeycloakSetupEnvironment] turns the chosen parameter set into the orchestrator's runtime values.
 * Without it the start fails far from the cause with "Could not resolve placeholder". The tests check
 * the derivation and the registration.
 */
class KeycloakSetupEnvironmentTest : BehaviorSpec({

    fun environmentWith(variant: String, profile: String = "keycloak"): MockEnvironment {
        val env = MockEnvironment()
        env.setActiveProfiles(profile)
        env.propertySources.addFirst(
            MapPropertySource(
                "test-setup",
                mapOf(
                    "keycloak-setup.variant" to variant,
                    "keycloak-setup.base.realmName" to "Demo",
                    "keycloak-setup.base.realmDisplayName" to "Demo",
                    "keycloak-setup.base.loginTheme" to "orchestrator",
                    "keycloak-setup.base.browserClientId" to "identity-demo-web",
                    "keycloak-setup.base.adminApiClientId" to "orchestrator-admin",
                    "keycloak-setup.base.appTokenClientId" to "orchestrator-app-token",
                    "keycloak-setup.base.browserRedirectUris" to "http://localhost:8080/*",
                    "keycloak-setup.base.orchestratorBaseUrl" to "http://localhost:8080",
                    "keycloak-setup.base.publicOrchestratorBaseUrl" to "http://localhost:8080",
                    "keycloak-setup.base.peerAuthIssuer" to "identity-demo-keycloak",
                    "keycloak-setup.base.peerAuthAudience" to "identity-demo-orchestrator",
                    "keycloak-setup.base.keycloakBaseUrl" to "https://localhost:8543",
                    "keycloak-setup.base.publicKeycloakBaseUrl" to "https://localhost:8543",
                    "keycloak-setup.base.trustSelfSignedCertificate" to "false",
                    "keycloak-setup.variants.compose.trustSelfSignedCertificate" to "true",
                    "keycloak-setup.variants.remote.keycloakBaseUrl" to "https://keycloak.example.org",
                    "keycloak-setup.variants.compose.keycloakBaseUrl" to "https://keycloak:8443",
                    "keycloak-setup.variants.compose.orchestratorBaseUrl" to "http://orchestrator:8080",
                ),
            ),
        )
        return env
    }

    given("the keycloak profile with the compose variant") {
        val env = environmentWith("compose")

        `when`("the environment is post-processed") {
            KeycloakSetupEnvironment().postProcessEnvironment(env, SpringApplication())

            then("the variant wins over the base wherever the value flows in") {
                env.getProperty("keycloak-sync.base-url") shouldBe "https://keycloak:8443"
                env.getProperty("keycloak-migrate.base-url") shouldBe "https://keycloak:8443"
                env.getProperty("kc.peer-auth.jwks-uri") shouldBe "https://keycloak:8443/realms/Demo/orchestrator-jwks/.well-known/jwks.json"
                env.getProperty("keycloak-migrate.admin-username").shouldBeNull()
            }

            then("the self-signed certificate is trusted, because the variant switches it on explicitly") {
                env.getProperty("keycloak-tls.trust-self-signed") shouldBe "true"
            }

            then("realm, clients and peer-auth values come from the base") {
                env.getProperty("keycloak-sync.realm") shouldBe "Demo"
                env.getProperty("keycloak-sync.admin-client-id") shouldBe "orchestrator-admin"
                env.getProperty("keycloak-sync.app-client-id") shouldBe "orchestrator-app-token"
                env.getProperty("keycloak-web.browser-client-id") shouldBe "identity-demo-web"
                env.getProperty("kc.peer-auth.issuer") shouldBe "identity-demo-keycloak"
                env.getProperty("kc.peer-auth.audience") shouldBe "identity-demo-orchestrator"
            }

            then("the browser gets the public address, not the variant's server-to-server one") {
                env.getProperty("keycloak-sync.public-base-url") shouldBe "https://localhost:8543"
            }
        }
    }

    given("the keycloak profile with a variant that does not switch on trust") {
        val env = environmentWith("remote")

        `when`("the environment is post-processed") {
            KeycloakSetupEnvironment().postProcessEnvironment(env, SpringApplication())

            then("Keycloak's certificate is not trusted") {
                env.getProperty("keycloak-tls.trust-self-signed") shouldBe "false"
            }
        }
    }

    given("the default profile instead of the keycloak profile") {
        val env = environmentWith("compose", profile = "default")

        `when`("the environment is post-processed") {
            KeycloakSetupEnvironment().postProcessEnvironment(env, SpringApplication())

            then("it stays out - the default profile's own kc.* values (empty issuer) are left alone") {
                env.getProperty("keycloak-sync.base-url").shouldBeNull()
            }
        }
    }

    given("the keycloak profile with an unknown variant") {
        val env = environmentWith("doesnotexist")

        `when`("the environment is post-processed") {
            val result = runCatching { KeycloakSetupEnvironment().postProcessEnvironment(env, SpringApplication()) }

            then("it names the unknown variant and the known ones, instead of quietly doing nothing") {
                val failure = shouldThrow<Exception> { result.getOrThrow() }
                failure.message shouldContain "doesnotexist"
                failure.message shouldContain "compose"
            }
        }
    }

    // Without this entry Spring does not load the post-processor. `.imports` files do not help here,
    // they apply only to auto-configurations.
    given("META-INF/spring.factories") {
        val registration = ClassPathResource("META-INF/spring.factories").inputStream.bufferedReader().readText()

        then("it registers KeycloakSetupEnvironment as an EnvironmentPostProcessor") {
            registration shouldContain "org.springframework.boot.EnvironmentPostProcessor"
            registration shouldContain KeycloakSetupEnvironment::class.java.name
        }
    }
})
