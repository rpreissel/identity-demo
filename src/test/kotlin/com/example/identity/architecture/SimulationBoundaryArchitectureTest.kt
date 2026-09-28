package com.example.identity.architecture

import com.example.identity.contract.tool_api.ModuleId
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchCondition
import com.tngtech.archunit.lang.ConditionEvents
import com.tngtech.archunit.lang.SimpleConditionEvent
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty

/**
 * The line between the production-ready core and the simulated foreign systems (ADR-35).
 *
 * The core may only rely on what the real system would promise. So it reaches a simulation only
 * along an edge named here, with its reason, and otherwise through a port in `tool_api`. The
 * orchestrator declares no Modulith dependencies, so this rule says which of its classes may see one.
 */
class SimulationBoundaryArchitectureTest : BehaviorSpec({

    val everything = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.example.identity")

    /** Every module in the simulation group, plus the demo's seed data for the person register. */
    val simulationGroup = "${ModuleId.ROOT_PACKAGE}.simulation."
    val demoSeed = "${ModuleId.ROOT_PACKAGE}.demo.demo_seed"

    /** Named edges from the core into a simulation: source package (or class) -> simulation, with the reason. */
    data class Edge(val from: String, val to: String, val why: String)
    val edges = listOf(
        // A procedure talks to its foreign system directly (docs/08-projektrahmen.md M-3). The
        // person register is the exception: it is reached over ports only (ADR-31 addendum).
        Edge("com.example.identity.tools.auth_kobil", "com.example.identity.simulation.kobil", "KOBIL is the auth_kobil procedure's foreign system"),
        Edge("com.example.identity.tools.ident_nect", "com.example.identity.simulation.nect", "Nect is the ident_nect procedure's foreign system"),
        Edge("com.example.identity.tools.auth_sms", "com.example.identity.simulation.sms", "the SMS provider"),
        Edge("com.example.identity.tools.auth_email", "com.example.identity.simulation.mail", "the mail server"),
    )

    /** The simulated module [target] belongs to, as its base package, or `null` for the core. */
    fun simulationOf(target: JavaClass): String? = when {
        target.packageName.startsWith(simulationGroup) ->
            simulationGroup + target.packageName.removePrefix(simulationGroup).substringBefore('.')
        target.packageName == demoSeed || target.packageName.startsWith("$demoSeed.") -> demoSeed
        else -> null
    }

    fun allowed(source: JavaClass, simulation: String): Boolean =
        simulationOf(source) != null ||
            edges.any { edge ->
                edge.to == simulation &&
                    (source.name == edge.from || source.name.startsWith("${edge.from}$") ||
                        source.packageName == edge.from || source.packageName.startsWith("${edge.from}."))
            }

    val reachSimulationsOnlyAlongNamedEdges = object : ArchCondition<JavaClass>("reach a simulation only along a named edge") {
        override fun check(source: JavaClass, events: ConditionEvents) {
            source.directDependenciesFromSelf
                .mapNotNull { dependency -> simulationOf(dependency.targetClass)?.let { it to dependency } }
                .filterNot { (simulation, _) -> allowed(source, simulation) }
                .forEach { (_, dependency) -> events.add(SimpleConditionEvent.violated(source, dependency.description)) }
        }
    }

    given("the core (everything that is not itself a simulation)") {
        then("it reaches a simulated foreign system only along a named edge, otherwise through a port") {
            classes()
                .that().resideInAPackage("com.example.identity..")
                .should(reachSimulationsOnlyAlongNamedEdges)
                .because("the core may rely only on what the real system would promise - its port (ADR-35)")
                .check(everything)
        }
    }

    given("the named edges") {
        then("each is still used - a stale edge would silently widen the boundary") {
            fun isSource(javaClass: JavaClass, edge: Edge) =
                javaClass.name == edge.from || javaClass.name.startsWith("${edge.from}$") ||
                    javaClass.packageName == edge.from || javaClass.packageName.startsWith("${edge.from}.")
            edges.filterNot { edge ->
                everything.any { source ->
                    isSource(source, edge) && source.directDependenciesFromSelf.any { simulationOf(it.targetClass) == edge.to }
                }
            }.shouldBeEmpty()
        }
    }
})
