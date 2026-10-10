package com.example.identity.kcext;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/**
 * The packages of the extension depend on each other in one direction only. What several of them
 * share - wire records, note names - lives in {@code model}, which depends on none of them.
 */
class ExtensionArchitectureTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.example.identity.kcext");

    @Test
    void thePackagesAreFreeOfCycles() {
        slices().matching("com.example.identity.kcext.(*)..")
                .should().beFreeOfCycles()
                .check(CLASSES);
    }

    @Test
    void theModelDependsOnNoOtherPackageOfTheExtension() {
        noClasses().that().resideInAPackage("com.example.identity.kcext.model..")
                .should().dependOnClassesThat().resideInAnyPackage(
                        "com.example.identity.kcext.client..", "com.example.identity.kcext.event..",
                        "com.example.identity.kcext.federation..", "com.example.identity.kcext.grant..",
                        "com.example.identity.kcext.login..", "com.example.identity.kcext.resource..",
                        "com.example.identity.kcext.token..", "com.example.identity.kcext.webtool..")
                .check(CLASSES);
    }
}
