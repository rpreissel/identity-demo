package com.example.identity.architecture

import com.example.identity.IdentityApplication
import com.example.identity.contract.tool_api.ModuleId
import com.tngtech.archunit.core.domain.JavaClass
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldNotBeEmpty
import org.springframework.modulith.core.ApplicationModules

/**
 * The line between the production-ready core and the simulated foreign systems (ADR-35). Which
 * module may reach which simulation, each module declares itself (`allowedDependencies`), and
 * [ModulithStructureTest] verifies it. That check sees only modules, so this rule covers the rest:
 * a class outside every module reaches neither a simulation nor the demo seed data.
 */
class SimulationBoundaryArchitectureTest : BehaviorSpec({

    val modules = ApplicationModules.of(IdentityApplication::class.java)
    val simulationGroup = "${ModuleId.ROOT_PACKAGE}.simulation."
    val demoSeed = "${ModuleId.ROOT_PACKAGE}.demo.demo_seed"

    fun inNoModule(javaClass: JavaClass) = modules.getModuleByType(javaClass.name).isEmpty

    fun isSimulated(target: JavaClass) =
        target.packageName.startsWith(simulationGroup) || target.packageName == demoSeed || target.packageName.startsWith("$demoSeed.")

    given("the classes outside every module") {
        val outside = MAIN_CLASSES.filter(::inNoModule)

        then("there are some - at least the application class") {
            outside.shouldNotBeEmpty()
        }

        then("none reaches a simulated foreign system or the demo seed data") {
            outside
                .flatMap { source -> source.directDependenciesFromSelf.filter { isSimulated(it.targetClass) } }
                .map { it.description }
                .shouldBeEmpty()
        }
    }
})
