package com.example.identity.architecture

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import io.kotest.core.spec.style.BehaviorSpec

/**
 * `tool_api` is a contract, not a module with runtime logic (docs/08-projektrahmen.md M-2): it
 * holds interfaces, value types, DTOs and constants, never a Spring bean, a scheduled job or
 * configuration. Whatever needs a bean belongs to the module that implements the port.
 */
class ToolApiArchitectureTest : BehaviorSpec({

    val toolApi = ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.example.identity.contract.tool_api")

    given("the tool_api module") {
        then("no class is a Spring bean, configuration or scheduled job") {
            noClasses().that().resideInAPackage("com.example.identity.contract.tool_api..")
                .should().beAnnotatedWith("org.springframework.stereotype.Component")
                .orShould().beAnnotatedWith("org.springframework.stereotype.Service")
                .orShould().beAnnotatedWith("org.springframework.web.bind.annotation.RestController")
                .orShould().beAnnotatedWith("org.springframework.context.annotation.Configuration")
                .orShould().beAnnotatedWith("org.springframework.boot.context.properties.ConfigurationProperties")
                .orShould().dependOnClassesThat().resideInAPackage("org.springframework.scheduling..")
                .because("tool_api is a contract without runtime logic (docs/08-projektrahmen.md M-2)")
                .check(toolApi)
        }
    }
})
