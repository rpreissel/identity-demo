package com.example.identity.core.orchestrator

import com.example.identity.architecture.mainClassesIn
import com.example.identity.architecture.MAIN_CLASSES
import com.example.identity.core.account.AuthMethodView
import com.example.identity.core.account.AccountProfile
import com.example.identity.core.orchestrator.channel.DisclosingDemoDisclosure
import com.example.identity.core.orchestrator.channel.WithheldDemoDisclosure
import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.AccessTarget.CodeUnitAccessTarget
import com.tngtech.archunit.core.domain.JavaCall
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.kotest.core.spec.style.BehaviorSpec
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import com.example.identity.core.orchestrator.domain.AuthIntent

/**
 * Dependency directions within the orchestrator, which Spring Modulith's `ApplicationModules.verify()`
 * does not see: it only checks boundaries between top-level modules.
 */
class OrchestratorArchitectureTest : BehaviorSpec({

    // Test code has deliberate exceptions (unit tests construct DefaultAuthPolicy without Spring);
    // these rules are about production layering.
    val classes = mainClassesIn("com.example.identity.core.orchestrator")

    given("the journey package's generic machine (JourneyService, IntentStrategy, Decision, JourneyState, ...)") {
        then("it never depends on one concrete IntentStrategy implementation") {
            noClasses()
                .that().resideInAnyPackage(
                    "com.example.identity.core.orchestrator.journey",
                    "com.example.identity.core.orchestrator.domain.journey",
                    "com.example.identity.core.orchestrator.domain.journey.state"
                )
                .should().dependOnClassesThat().resideInAPackage("com.example.identity.core.orchestrator.domain.journey.strategy..")
                .because(
                    "the machine is generic over every AuthIntent via IntentStrategy/strategiesByIntent - " +
                        "depending on one concrete strategy class breaks that (and was exactly today's bug: " +
                        "JourneyService referenced DeleteAccountStrategy.REQUIRED_ACR directly instead of a " +
                        "constant in the generic journey package)"
                )
                .check(classes)
        }
    }

    given("the orchestrator's domain (docs/adr/ADR-040-fachkern-im-paket-domain.md)") {
        then("it uses no framework - what it says can be read without knowing Spring, JPA or Jackson") {
            noClasses()
                .that().resideInAPackage("com.example.identity.core.orchestrator.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta..", "org.springframework..", "org.hibernate..", "tools.jackson..", "com.fasterxml..", "org.slf4j..",
                    "io.swagger..", "java.util.logging.."
                )
                .because("the domain is where a developer reads the rules; persistence, serialization and wiring live around it")
                .check(classes)
        }

        then("it depends on nothing else in the orchestrator - everything else may depend on it") {
            noClasses()
                .that().resideInAPackage("com.example.identity.core.orchestrator.domain..")
                .should().dependOnClassesThat(
                    JavaClass.Predicates.resideInAPackage("com.example.identity.core.orchestrator..")
                        .and(JavaClass.Predicates.resideOutsideOfPackage("com.example.identity.core.orchestrator.domain.."))
                )
                .because("the domain is the bottom of the orchestrator: application and infrastructure use it, never the other way")
                .check(classes)
        }

        then("it sees the account module only through its read view") {
            noClasses()
                .that().resideInAPackage("com.example.identity.core.orchestrator.domain..")
                .should().dependOnClassesThat(
                    JavaClass.Predicates.resideInAPackage("com.example.identity.core.account..")
                        .and(DescribedPredicate.not(JavaClass.Predicates.belongToAnyOf(AccountProfile::class.java, AuthMethodView::class.java)))
                )
                .because("docs/08-projektrahmen.md: the domain may read AccountProfile, never a service or an entity of account")
                .check(classes)
        }
    }

    given("the journey entity (AuthJourney) and its repository") {
        then("outside the journey package, only RunningJourney reaches a journey - and retention deletes old rows") {
            // docs/invarianten.md I-2: a finished journey takes no more tool results. RunningJourney's
            // factory refuses a finished journey, which holds only while nobody else reaches the entity.
            noClasses()
                .that().resideOutsideOfPackages(
                    "com.example.identity.core.orchestrator.journey..",
                    "com.example.identity.core.orchestrator.retention.."
                )
                .should().dependOnClassesThat().haveFullyQualifiedName("com.example.identity.core.orchestrator.journey.AuthJourney")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.example.identity.core.orchestrator.journey.AuthJourneyRepository")
                .because("the channel layer acts on journeys only through RunningJourney")
                .check(classes)
        }
    }

    given("the account a journey ran for (AuthJourney.accountId)") {
        then("no production code reads it - the account in hand is the channel's") {
            // A second readable copy of the same fact invites `journey.accountId ?: channel.accountId`
            // with a dead branch.
            noClasses()
                .should().callMethodWhere(
                    DescribedPredicate.describe<JavaCall<*>>("read AuthJourney.accountId") { call ->
                        call.target.owner.fullName == "com.example.identity.core.orchestrator.journey.AuthJourney" &&
                            call.target.kotlinName == "getAccountId"
                    }
                )
                .because("AuthJourney.accountId is an audit record; decisions read ChannelSession.accountId")
                .check(classes)
        }
    }

    given("the versioned HTTP layer (api.v1)") {
        then("nothing outside it depends on it") {
            // api.v1 is the adapter: routes, request bodies, parameter binding, OpenAPI. The arrow
            // points one way, api.v1 -> channel/journey/session, so a second version can sit next to v1.
            noClasses()
                .that().resideOutsideOfPackage("com.example.identity.core.orchestrator.api..")
                .should().dependOnClassesThat().resideInAPackage("com.example.identity.core.orchestrator.api..")
                .because(
                    "api.v1 is the adapter; logic and response shapes live below it (channel, tool_api), " +
                        "so a new API version can sit next to v1 without importing it"
                )
                .check(classes)
        }
    }

    given("AuthPolicy's single implementation (DefaultAuthPolicy)") {
        then("nothing outside the policy package depends on it directly - only DomainBeans creates it") {
            noClasses()
                .that().resideOutsideOfPackage("com.example.identity.core.orchestrator.domain.policy..")
                .and().doNotHaveFullyQualifiedName("com.example.identity.core.orchestrator.DomainBeans")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.example.identity.core.orchestrator.domain.policy.DefaultAuthPolicy")
                .because("every caller is meant to go through the AuthPolicy interface (Spring-injected), never the concrete implementation - the same reasoning as the journey/strategy rule above")
                .check(classes)
        }
    }

    given("IntentStrategy implementations (docs/04-orchestrierung.md #4, Decision: \"Die Strategie bekommt nie Services, nur einen lesenden JourneyContext ... Sie entscheidet, sie wirkt nicht.\")") {
        then("they never depend on a @Service or @Repository - only on the read-only JourneyContext handed to next()/interpret()") {
            val isServiceOrRepository = DescribedPredicate.describe<JavaClass>("annotated with @Service or @Repository") { clazz ->
                clazz.isAnnotatedWith(Service::class.java) || clazz.isAnnotatedWith(Repository::class.java)
            }
            noClasses()
                .that().implement("com.example.identity.core.orchestrator.domain.journey.IntentStrategy")
                .should().dependOnClassesThat(isServiceOrRepository)
                .because(
                    "a strategy DECIDES, it never ACTS (IntentStrategy's own class doc) - account creation, " +
                        "evidence recording, device linking all happen once in JourneyService instead, so no " +
                        "intent can forget them or duplicate them differently; a strategy that could inject a " +
                        "service could act directly and silently break that guarantee"
                )
                .check(classes)
        }
    }

    given("the acting phase (JourneyActionExecutor, docs/04-orchestrierung.md #4, \"Die vier Phasen eines Uebergangs\")") {
        then("it never depends on the driving phase (JourneyService) or on a concrete IntentStrategy") {
            noClasses()
                .that().haveFullyQualifiedName("com.example.identity.core.orchestrator.journey.JourneyActionExecutor")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.example.identity.core.orchestrator.journey.JourneyService")
                .orShould().dependOnClassesThat().resideInAPackage("com.example.identity.core.orchestrator.domain.journey.strategy..")
                .because(
                    "the executor WRITES and returns - it never advances a journey, never routes and never starts a " +
                        "sub-journey; that one-way dependency is what keeps the recursion in JourneyService." +
                        "applyTransition the only recursion the machine has"
                )
                .check(classes)
        }
    }

    given("the routing phase (JourneyRouting)") {
        then("it stays a pure function of (state, availableTools) - no repository, no journey writing") {
            noClasses()
                .that().haveFullyQualifiedName("com.example.identity.core.orchestrator.journey.JourneyRouting")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.example.identity.core.orchestrator.journey.AuthJourneyRepository")
                .orShould().dependOnClassesThat().haveFullyQualifiedName("com.example.identity.core.orchestrator.journey.AuthJourney")
                .because(
                    "\"next is a pure function of the state\" (docs/04-orchestrierung.md #4) is only checkable by " +
                        "reading one small class as long as that class cannot reach the journey itself"
                )
                .check(classes)
        }
    }

    // Account takeover: a session bound to an account it never proved it owns. It arises when the
    // safety check lives per action handler while a strategy chooses the handler. The rules below make
    // identity resolution, account absorption and device linking reachable from one class only, so no
    // strategy or tool order can reach a takeover path that skips the gate.
    val gate = "com.example.identity.core.orchestrator.journey.JourneyActionExecutor"
    // A lambda taking a value class compiles to a class of its own (`JourneyActionExecutor$accountOf$1`).
    val gateAndItsLambdas = "${Regex.escape(gate)}(\\$.*)?"

    // Scanned across the whole application, not just `orchestrator`: a tool module injecting
    // IdentityResolver for itself is exactly the case a narrower scope would miss.
    val everything = MAIN_CLASSES

    given("the backend's console") {
        then("nothing prints to STDOUT/STDERR - codes and recipients must not end up in a container log") {
            noClasses()
                // The Keycloak migration runner is a separate build tool that reports its progress on
                // the console by design; it never sees a code or a recipient.
                .that().resideOutsideOfPackage("com.example.identity.kcmigrate..")
                .should().dependOnClassesThat().haveFullyQualifiedName("kotlin.io.ConsoleKt")
                .orShould().accessField(System::class.java, "out")
                .orShould().accessField(System::class.java, "err")
                .because(
                    "the println calls this replaced put TANs and email codes together with phone numbers " +
                        "and addresses on STDOUT, regardless of demo mode; logging goes through SLF4J, " +
                        "and a simulated provider keeps what it sent in its own outbox"
                )
                .check(everything)
        }
    }

    given("the Keycloak migration library") {
        then("only the orchestrator's Keycloak connection uses it") {
            // kcmigrate lives in keycloak-migrations, carries no @ApplicationModule and is therefore
            // no Modulith module; this rule is its boundary instead of allowedDependencies.
            noClasses()
                .that().resideOutsideOfPackage("com.example.identity.core.orchestrator.keycloak..")
                .and().resideOutsideOfPackage("com.example.identity.kcmigrate..")
                // Reads the account id off a federated Keycloak user, the one helper outside kc.
                .and().doNotHaveFullyQualifiedName("com.example.identity.core.orchestrator.admin.ActiveSessions")
                .should().dependOnClassesThat().resideInAPackage("com.example.identity.kcmigrate..")
                .because("the realm setup belongs to the Keycloak connection, not to tools or other modules")
                .check(everything)
        }
    }

    given("identity resolution (IdentityResolver - \"does this attested identity belong to an existing account?\")") {
        then("only the acting phase can ask, so the answer can never be acted on without its gate") {
            noClasses()
                .that().haveNameNotMatching(gateAndItsLambdas)
                // The port and its single implementation, named individually so a second class in
                // either package cannot inherit the exemption.
                .and().doNotHaveFullyQualifiedName("com.example.identity.contract.tool_api.directory.IdentityResolver")
                .and().doNotHaveFullyQualifiedName("com.example.identity.core.account.application.IdentityMatchingService")
                .should().dependOnClassesThat().haveFullyQualifiedName("com.example.identity.contract.tool_api.directory.IdentityResolver")
                .because(
                    "resolving claims to an existing account is the first half of a takeover; the second half " +
                        "(actually binding the session to it) is gated in JourneyActionExecutor.accountOf. A " +
                        "strategy that could resolve for itself could act on the answer before that gate - " +
                        "exactly the shape of both account-takeover bugs found in this codebase"
                )
                .check(everything)
        }
    }

    // Tool session snapshots (a resolved account, a targeted enrollment) cannot be used against a
    // journey that has moved on: applyOutcome/abandon take an AuthorizedToolContext, so a write path
    // that skips the check does not compile. No ArchUnit rule is needed (tool_api/ToolJourney.kt).

    given("absorbing one account into another and linking a device to an account") {
        then("both happen in the acting phase only - never from a strategy, a controller or a tool") {
            val crossesAccounts = DescribedPredicate.describe<JavaCall<*>>(
                "absorb an account into another, or link a device to an account"
            ) { call ->
                val target = call.target
                (target.owner.fullName == "com.example.identity.core.account.AccountService" &&
                    target.kotlinName == "absorbDisposableAccount") ||
                    (target.owner.fullName == "com.example.identity.core.orchestrator.session.SessionManagementService" &&
                        target.kotlinName == "linkDeviceToAccount")
            }
            noClasses()
                .that().haveNameNotMatching(gateAndItsLambdas)
                .should().callMethodWhere(crossesAccounts)
                .because(
                    "these two are the only writes that move a session/device onto an account it did not " +
                        "already hold. Keeping them in one class is what makes \"a takeover always passes " +
                        "accountOf\" checkable by reading one file instead of trusting every caller"
                )
                .check(everything)
        }
    }

    given("the DeviceAccountLink table itself") {
        then("only the service that owns the rebind rule may write it - not the repository directly") {
            // Closes the way around the rule above: saving a DeviceAccountLink through the repository
            // would bind a device with no rebind check and no revocation of the previous account's
            // device credentials, and the call-based rule would not see it.
            noClasses()
                .that().doNotHaveFullyQualifiedName("com.example.identity.core.orchestrator.session.SessionManagementService")
                .and().doNotHaveFullyQualifiedName("com.example.identity.core.orchestrator.session.AccountDeletionService")
                .and().doNotHaveFullyQualifiedName("com.example.identity.core.orchestrator.session.DeviceAccountLinkRepository")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.example.identity.core.orchestrator.session.DeviceAccountLinkRepository")
                .because(
                    "SessionManagementService.linkDeviceToAccount is where the 1:1 device->account invariant " +
                        "lives (docs/09-dpop.md #3) and AccountDeletionService is the one legitimate bulk " +
                        "remover; anything else reaching the table directly would bypass both"
                )
                .check(everything)
        }
    }

    given("outbound Keycloak Admin API calls") {
        then("no @Transactional class makes one - a DB transaction never spans a network round trip") {
            // Holding a transaction across a remote call keeps row locks for as long as Keycloak takes to
            // answer. Under load one slow Keycloak stalls the orchestrator, and it looks like a database
            // problem. Listeners act AFTER_COMMIT instead (KeycloakAccountRemovalListener).
            noClasses()
                .that().areAnnotatedWith(Transactional::class.java)
                .and().resideOutsideOfPackage("com.example.identity.core.orchestrator.keycloak..")
                // The one declared exception: minting an account token is a Keycloak round trip whose
                // result is the response, and its transaction writes the refresh-token cache back onto
                // the AppTokenSession. One call per token request or per transition to AUTHENTICATED
                // (ADR-43), never per row.
                .and().doNotHaveFullyQualifiedName("com.example.identity.core.orchestrator.session.KeycloakTokenProvider")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("com.example.identity.core.orchestrator.keycloak.KeycloakAdminClient")
                .because(
                    "a transaction that spans a Keycloak round trip holds row locks for the duration of a " +
                        "remote call; publish an event and act on it AFTER_COMMIT instead, the way " +
                        "KeycloakAccountRemovalListener and KeycloakSessionLogoutListener do"
                )
                .check(everything)
        }
    }

    given("the demo block of a response") {
        then("only DemoDisclosure builds one, so a deployment can switch disclosure off for good") {
            // `demo` carries a plaintext TAN, the demo password and persona data. With construction
            // confined to one property-gated bean, `demo.mode=false` removes the values from every response.
            noClasses()
                .that().resideOutsideOfPackage("com.example.identity.contract.tool_api..")
                .and().doNotHaveFullyQualifiedName(DisclosingDemoDisclosure::class.java.name)
                .and().doNotHaveFullyQualifiedName(WithheldDemoDisclosure::class.java.name)
                .should().callConstructorWhere(
                    DescribedPredicate.describe<JavaCall<*>>("construct a DemoInfo") { call ->
                        call.target.owner.fullName == "com.example.identity.contract.tool_api.envelope.DemoInfo"
                    }
                )
                .because(
                    "DemoDisclosure is the single place that decides whether this deployment discloses " +
                        "demo-only values at all; a second construction site would silently reinstate the " +
                        "unconditional path"
                )
                .check(everything)
        }
    }

    given("the orchestrator's own packages") {
        then("they form a DAG - no package depends, directly or indirectly, on one that depends on it") {
            // Spring Modulith checks boundaries between top-level modules, never inside one. A cycle
            // means two packages can only be understood, tested and changed together; shared names
            // belong in `domain`, which depends on nothing.
            slices()
                .matching("com.example.identity.core.orchestrator.(*)..")
                .should().beFreeOfCycles()
                .check(everything)
        }
    }
})

/**
 * The name in the Kotlin source. A function taking or returning a value class carries a hash
 * suffix on the JVM (`linkDeviceToAccount-U3iWffw`); a rule that matches the plain name would
 * silently match nothing.
 */
private val CodeUnitAccessTarget.kotlinName: String get() = name.substringBefore('-')
