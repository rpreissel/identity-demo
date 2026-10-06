package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.session.KeycloakTokenProvider
import com.example.identity.core.orchestrator.session.MockTokenProvider
import com.example.identity.core.orchestrator.session.TokenProvider
import com.example.identity.core.account.ChangeLogLookupKeys
import com.example.identity.core.account.ClaimEncryptionKeys
import com.example.identity.core.account.DataKeyWrapping
import com.example.identity.core.orchestrator.session.DataKeyRepository
import com.example.identity.demo.demo_mode.DemoMode
import io.kotest.assertions.throwables.shouldNotThrowAny
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk

/**
 * Outside demo mode, no demo default may survive the start: the constructor refuses it. In demo mode
 * the constructor checks nothing, so [ProductionModeCheck.violations] is read there directly.
 */
class ProductionModeCheckTest : BehaviorSpec({

    val secret = "x".repeat(32)

    /** Starts the check; every parameter defaults to a value fit for real people. */
    fun check(
        demoMode: Boolean = false,
        adminPassword: String = "{bcrypt}\$2a\$10\$abcdefghijklmnopqrstuv",
        h2Console: Boolean = false,
        otpPepper: String = secret,
        lookupSecret: String = secret,
        kmsSimulated: Boolean = false,
        orphanedKekVersions: Set<String> = emptySet(),
        orphanedDataKekVersions: Set<String> = emptySet(),
        trustSelfSigned: Boolean = false,
        keycloakBaseUrl: String = "https://keycloak.example",
        orchestratorBaseUrlForKeycloak: String = "https://orchestrator.example",
        usesDemoLookupSecret: Boolean = false,
        orphanedLookupKeyIds: Set<String> = emptySet(),
        apiDocs: Boolean = false,
        tokenProvider: TokenProvider = mockk<KeycloakTokenProvider>(),
    ) = ProductionModeCheck(
        DemoMode(demoMode),
        mockk<ChangeLogLookupKeys> {
            every { usesDemoSecret() } returns usesDemoLookupSecret
            every { orphanedKeyIds() } returns orphanedLookupKeyIds
        },
        adminPassword, h2Console, otpPepper, lookupSecret, trustSelfSigned, keycloakBaseUrl, orchestratorBaseUrlForKeycloak, apiDocs,
        tokenProvider,
        mockk<ClaimEncryptionKeys> {
            every { kmsSimulated() } returns kmsSimulated
            every { orphanedKekVersions() } returns orphanedKekVersions
        },
        mockk<DataKeyWrapping> { every { knownVersions } returns setOf("1") },
        mockk<DataKeyRepository> { every { kekVersions() } returns orphanedDataKekVersions },
    )

    given("demo mode with every demo default in place") {
        `when`("the orchestrator starts") {
            val result = runCatching {
                check(
                    demoMode = true, adminPassword = "admin", h2Console = true, otpPepper = "", lookupSecret = "", trustSelfSigned = true,
                    keycloakBaseUrl = "http://keycloak", orchestratorBaseUrlForKeycloak = "http://orchestrator", orphanedLookupKeyIds = setOf("1"),
                    orphanedKekVersions = setOf("1")
                )
            }

            then("it starts - the demo defaults are allowed, orphaned search keys and KEK versions only warn") {
                shouldNotThrowAny { result.getOrThrow() }
            }
        }
    }

    given("demo mode off with a configuration fit for real people") {
        `when`("the orchestrator starts") {
            val result = runCatching { check() }

            then("it starts") {
                shouldNotThrowAny { result.getOrThrow() }
            }
        }
    }

    given("demo mode off with every demo default still in place") {
        `when`("the orchestrator starts") {
            val result = runCatching {
                check(
                    adminPassword = "admin", h2Console = true, otpPepper = "", lookupSecret = "short", kmsSimulated = true, trustSelfSigned = true,
                    keycloakBaseUrl = "http://keycloak:8080", orchestratorBaseUrlForKeycloak = "http://orchestrator:8080", apiDocs = true
                )
            }

            then("it refuses to start and names each of them at once") {
                val failure = shouldThrow<IllegalStateException> { result.getOrThrow() }
                listOf("demo.admin.password", "spring.h2.console", "springdoc.api-docs", "otp-pepper", "lookup-secret", "Modul kms", "trustSelfSignedCertificate", "http://keycloak", "http://orchestrator").forEach {
                    failure.message!! shouldContain it
                }
            }
        }
    }

    given("accounts wrapped under a KEK version that is no longer configured") {
        val check = check(demoMode = true, orphanedKekVersions = setOf("1"))

        `when`("listing the violations") {
            val violations = check.violations()

            then("it is refused - those accounts could not read a single claim") {
                violations.single() shouldContain "zurueckgezogene Versionen"
            }
        }
    }

    given("orchestrator data keys wrapped under a KEK version that is no longer configured") {
        val check = check(demoMode = true, orphanedDataKekVersions = setOf("0"))

        `when`("listing the violations") {
            val violations = check.violations()

            then("it is refused like an orphaned account key - the working data under it is unreadable") {
                violations.single() shouldContain "orchestrator.data_key"
            }
        }
    }

    given("the simulated KMS") {
        val check = check(demoMode = true, kmsSimulated = true)

        `when`("listing the violations") {
            val violations = check.violations()

            then("it is refused - every key would lie in our own database") {
                violations.single() shouldContain "Modul kms"
            }
        }
    }

    given("an admin password in plain text that is not the demo value") {
        val check = check(demoMode = true, adminPassword = "correct-horse-battery-staple")

        `when`("listing the violations") {
            val violations = check.violations()

            then("it is refused anyway - the login needs a hash") {
                violations.single() shouldContain "Klartext"
            }
        }
    }

    given("an admin password marked {noop}") {
        val check = check(demoMode = true, adminPassword = "{noop}correct-horse-battery-staple")

        `when`("listing the violations") {
            val violations = check.violations()

            then("it is refused - {noop} is plain text in disguise") {
                violations.single() shouldContain "{noop}"
            }
        }
    }

    given("demo mode off without the keycloak profile") {
        val check = check(demoMode = true, tokenProvider = mockk<MockTokenProvider>())

        `when`("listing the violations") {
            val violations = check.violations()

            then("it is refused - the App channel would hand out unsigned mock tokens") {
                violations.single() shouldContain "Profil keycloak"
            }
        }
    }

    given("no Keycloak configured (the non-keycloak profile)") {
        val check = check(demoMode = true, keycloakBaseUrl = "", orchestratorBaseUrlForKeycloak = "")

        `when`("listing the violations") {
            val violations = check.violations()

            then("the https rule does not apply") {
                violations.shouldBeEmpty()
            }
        }
    }

    // The change log's search keys (ADR-39).
    given("the public demo secret for the search keys, though long enough") {
        val check = check(demoMode = true, usesDemoLookupSecret = true)

        `when`("listing the violations") {
            val violations = check.violations()

            then("it is refused") {
                violations.single() shouldContain "Demo-Wert"
            }
        }
    }

    given("search keys no configured secret matches") {
        val check = check(demoMode = true, orphanedLookupKeyIds = setOf("1"))

        `when`("listing the violations") {
            val violations = check.violations()

            then("they are refused - those entries could no longer be found") {
                violations.single() shouldContain "[1]"
            }
        }
    }
})
