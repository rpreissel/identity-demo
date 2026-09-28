package com.example.identity

import com.example.identity.contract.tool_api.ModuleId
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.modulith.core.ApplicationModules
import org.springframework.test.context.ActiveProfiles

@SpringBootTest
@ActiveProfiles("test")
class IdentityApplicationTests : BehaviorSpec({

    given("the Spring application context") {
        then("it loads successfully") {
        }

        val modules = ApplicationModules.of(IdentityApplication::class.java)

        then("the modulith structure is valid") {
            modules.verify()
        }

        then("every module sits in one group and is named by its last package segment") {
            // The id doubles as schema, migration folder, API file and text bundle (docs/08-projektrahmen.md M-1).
            val misplaced = modules.map { module ->
                val pkg = module.basePackage.name
                val group = pkg.removePrefix("${ModuleId.ROOT_PACKAGE}.").substringBefore('.')
                val expected = pkg.substringAfterLast('.')
                when {
                    group !in GROUPS -> "$pkg: not in one of $GROUPS"
                    pkg.count { it == '.' } != ModuleId.ROOT_PACKAGE.count { it == '.' } + 2 -> "$pkg: not directly below its group"
                    module.identifier.toString() != expected -> "$pkg: id ${module.identifier} instead of $expected"
                    else -> null
                }
            }.filterNotNull()
            misplaced.shouldBeEmpty()
        }
    }
}) {
    companion object {
        /** Core, contracts, procedures, simulated foreign systems, demo-only parts. */
        val GROUPS = setOf("core", "contract", "tools", "simulation", "demo")
    }
}
