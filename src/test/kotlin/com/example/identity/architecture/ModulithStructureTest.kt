package com.example.identity.architecture

import com.example.identity.contract.tool_api.ModuleId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty

/** Core, contracts, procedures, simulated foreign systems, demo-only parts. */
private val GROUPS = setOf("core", "contract", "tools", "simulation", "demo")

/**
 * The Modulith structure, read from the classes alone, without a Spring context: every module
 * keeps to its declared dependencies (docs/08-projektrahmen.md Abschnitt 3).
 */
class ModulithStructureTest : BehaviorSpec({

    given("the application modules") {
        val modules = APPLICATION_MODULES

        `when`("their dependencies are verified") {
            val result = runCatching { modules.verify() }

            then("each module depends only on what it declares") {
                result.getOrThrow()
            }
        }

        then("every module sits in one group and is named by its last package segment") {
            // The id doubles as schema, migration folder, API file and text bundle (docs/08-projektrahmen.md M-1).
            val misplaced = modules.mapNotNull { module ->
                val pkg = module.basePackage.name
                val group = pkg.removePrefix("${ModuleId.ROOT_PACKAGE}.").substringBefore('.')
                val expected = pkg.substringAfterLast('.')
                when {
                    group !in GROUPS -> "$pkg: not in one of $GROUPS"
                    pkg.count { it == '.' } != ModuleId.ROOT_PACKAGE.count { it == '.' } + 2 -> "$pkg: not directly below its group"
                    module.identifier.toString() != expected -> "$pkg: id ${module.identifier} instead of $expected"
                    else -> null
                }
            }
            misplaced.shouldBeEmpty()
        }
    }
})
