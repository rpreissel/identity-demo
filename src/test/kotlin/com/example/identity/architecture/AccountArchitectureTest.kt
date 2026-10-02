package com.example.identity.architecture

import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices
import io.kotest.core.spec.style.BehaviorSpec

/**
 * The account module's layers (docs/adr/ADR-040-fachkern-im-paket-domain.md): `domain` holds the rules
 * (anchor decision, claim normalization, spelling rule), `application` the services that apply them,
 * `infrastructure` the entities and repositories.
 */
class AccountArchitectureTest : BehaviorSpec({

    val classes = mainClassesIn("com.example.identity.core.account")

    given("the account module's domain") {
        then("it uses no framework") {
            noClasses()
                .that().resideInAPackage("com.example.identity.core.account.domain..")
                .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta..", "org.springframework..", "org.hibernate..", "tools.jackson..", "com.fasterxml..", "org.slf4j..",
                    "io.swagger..", "java.util.logging.."
                )
                .because("the rules are read here, without knowing Spring, JPA or Jackson")
                .check(classes)
        }

        then("it knows neither the services nor the persistence of its own module") {
            noClasses()
                .that().resideInAPackage("com.example.identity.core.account.domain..")
                .should().dependOnClassesThat(
                    JavaClass.Predicates.resideInAnyPackage("com.example.identity.core.account.application..", "com.example.identity.core.account.infrastructure..")
                        .or(JavaClass.Predicates.simpleName("AccountService"))
                )
                .because("application and infrastructure use the domain, never the other way")
                .check(classes)
        }
    }

    given("the account module's persistence") {
        then("it does not reach up into the services that use it") {
            noClasses()
                .that().resideInAPackage("com.example.identity.core.account.infrastructure..")
                .should().dependOnClassesThat().resideInAPackage("com.example.identity.core.account.application..")
                .because("application reads and writes through infrastructure, never the other way")
                .check(classes)
        }
    }

    given("the account module's packages") {
        then("they form a DAG") {
            slices().matching("com.example.identity.core.account.(*)..").should().beFreeOfCycles().check(classes)
        }
    }
})
