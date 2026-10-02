package com.example.identity.architecture

import com.example.identity.demo.demo_mode.DemoSurface
import com.example.identity.core.orchestrator.keycloak.PeerAuthValidator
import com.example.identity.contract.tool_api.BindingKey
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.domain.JavaMethod
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Default-deny for the HTTP surface.
 *
 * DPoP is enforced by an argument resolver, not a filter: only a handler that declares a
 * `@BindingKey` parameter is bound to a channel. A new handler without one would be silently open.
 * So every handler must either declare it, or its controller must be named below - each exception
 * with the protection it relies on instead, and that protection checked where it can be.
 */
class ApiBoundaryArchitectureTest : BehaviorSpec({

        /** Controllers without `@BindingKey`, by the protection they rely on instead. */
    val guardedByAdminLogin = setOf(
        "com.example.identity.core.orchestrator.admin.AdminAccountsController",
        "com.example.identity.core.orchestrator.admin.AdminJourneyTraceController",
        "com.example.identity.core.orchestrator.admin.LoginThemeController",
        "com.example.identity.core.orchestrator.admin.Loa1LoginController",
        "com.example.identity.core.orchestrator.admin.AdminSessionsController",
        "com.example.identity.core.orchestrator.admin.RegistrationOrderController",
        "com.example.identity.core.orchestrator.admin.ToolAvailabilityController",
    )
    val guardedByPeerAuth = setOf(
        "com.example.identity.core.orchestrator.api.v1.keycloak.KeycloakChannelController",
        "com.example.identity.core.orchestrator.api.v1.keycloak.KeycloakAccountLookupController",
        "com.example.identity.core.orchestrator.api.v1.keycloak.KeycloakSignOutController",
        "com.example.identity.core.orchestrator.api.v1.keycloak.KeycloakInvitationLookupController",
    )
    val publicByDesign = mapOf(
        "com.example.identity.core.orchestrator.api.v1.TextsController" to "the wordings every client renders, before any channel exists",
        "com.example.identity.core.orchestrator.api.v1.tool.ToolCatalogController" to "which tools exist - no account, no channel state",
        "com.example.identity.core.orchestrator.keycloak.OrchestratorClientJwksController" to "public keys Keycloak verifies our client assertions against",
        "com.example.identity.core.orchestrator.keycloak.KeycloakResponseJwksController" to "public key Keycloak verifies our signed answers against",
        "com.example.identity.core.orchestrator.admin.ServerInfoController" to "demo overview under DEMO_API, read-only",
        "com.example.identity.core.orchestrator.admin.DemoLoginThemeController" to "demo theme switch under DEMO_API (AdminPaths), later hardening (ADR-35)",
        "com.example.identity.core.orchestrator.admin.DemoLoa1LoginController" to "demo loa1 switch under DEMO_API (AdminPaths), later hardening (ADR-35)",
        "com.example.identity.core.orchestrator.admin.DemoSessionsController" to "demo reset and session overview for the welcome page under DEMO_API, demo mode only (ADR-36)",
    )
    /** Simulated foreign systems (ADR-35): their own, deliberately unauthenticated demo surface. */
    val simulatedForeignSystems = setOf("com.example.identity.simulation..")
    val exempt = guardedByAdminLogin + guardedByPeerAuth + publicByDesign.keys

    val haveBindingKey = object : ArchCondition<JavaMethod>("have a @BindingKey parameter") {
        override fun check(method: JavaMethod, events: ConditionEvents) {
            if (method.parameters.none { it.isAnnotatedWith(BindingKey::class.java) }) {
                events.add(SimpleConditionEvent.violated(method, "${method.fullName} has no @BindingKey parameter"))
            }
        }
    }

    given("every HTTP handler of the backend") {
        then("declares @BindingKey (DPoP-bound) unless its controller is named with its own protection") {
            methods()
                .that().areDeclaredInClassesThat().areAnnotatedWith(RestController::class.java)
                .and().areDeclaredInClassesThat().resideOutsideOfPackages(*simulatedForeignSystems.toTypedArray())
                .and(notDeclaredIn(exempt))
                .and(isHandler())
                .should(haveBindingKey)
                .because("DPoP binds a request to its channel only through @BindingKey - a handler without it is open")
                .check(MAIN_CLASSES)
        }
    }

    given("the unauthenticated surfaces of the simulated foreign systems (ADR-35/36)") {
        then("exist only in demo mode - every controller there, and the demo's own switches, is a @DemoSurface") {
            classes()
                .that().areAnnotatedWith(RestController::class.java)
                .and().resideInAnyPackage(*simulatedForeignSystems.toTypedArray())
                .or().haveFullyQualifiedName("com.example.identity.core.orchestrator.admin.DemoLoginThemeController")
                .or().haveFullyQualifiedName("com.example.identity.core.orchestrator.admin.DemoLoa1LoginController")
                .or().haveFullyQualifiedName("com.example.identity.core.orchestrator.admin.DemoSessionsController")
                .should().beAnnotatedWith(DemoSurface::class.java)
                .because("reachable in an instance with real people, each would be a way to take over accounts")
                .check(MAIN_CLASSES)
        }
    }

    given("the controllers exempt because the admin login guards them") {
        then("they are mapped under ADMIN_API, the one path Spring Security protects (AdminSecurityConfig)") {
            val outside = guardedByAdminLogin.map { MAIN_CLASSES.get(it) }
                .filterNot { basePath(it).startsWith("/orchestrator/admin") }
                .map { "${it.name} -> ${basePath(it)}" }
            outside.shouldBeEmpty()
        }
    }

    given("the controllers exempt because Keycloak's peer-auth assertion guards them") {
        then("they actually validate it") {
            classes()
                .that(namedIn(guardedByPeerAuth))
                .should().dependOnClassesThat().areAssignableTo(PeerAuthValidator::class.java)
                .check(MAIN_CLASSES)
        }
    }

    given("the exception list itself") {
        then("names only controllers that exist - a stale entry would hide nothing and mislead") {
            exempt.filterNot { MAIN_CLASSES.contain(it) }.shouldBeEmpty()
        }
    }
})

private val HANDLER_ANNOTATIONS = listOf(
    GetMapping::class.java, PostMapping::class.java, PutMapping::class.java,
    PatchMapping::class.java, DeleteMapping::class.java, RequestMapping::class.java,
)

private fun isHandler() = object : com.tngtech.archunit.base.DescribedPredicate<JavaMethod>("are request handlers") {
    override fun test(method: JavaMethod) = HANDLER_ANNOTATIONS.any { method.isAnnotatedWith(it) }
}

private fun notDeclaredIn(names: Set<String>) =
    object : com.tngtech.archunit.base.DescribedPredicate<JavaMethod>("are not declared in an exempt controller") {
        override fun test(method: JavaMethod) = method.owner.name !in names
    }

private fun namedIn(names: Set<String>) =
    object : com.tngtech.archunit.base.DescribedPredicate<JavaClass>("are named in $names") {
        override fun test(javaClass: JavaClass) = javaClass.name in names
    }

private fun basePath(controller: JavaClass): String =
    controller.tryGetAnnotationOfType(RequestMapping::class.java).map { (it.value.firstOrNull() ?: it.path.firstOrNull()).orEmpty() }.orElse("")
