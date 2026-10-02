package com.example.identity.architecture

import com.example.identity.IdentityApplication
import com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage
import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.springframework.modulith.core.ApplicationModules

/**
 * The application's classes without the tests, imported once and shared by every ArchUnit spec:
 * an import of the whole classpath costs about a second.
 */
internal val MAIN_CLASSES: JavaClasses by lazy {
    ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.example.identity")
}

/** The classes in [pkg] and its subpackages, taken from [MAIN_CLASSES]. */
internal fun mainClassesIn(pkg: String): JavaClasses = MAIN_CLASSES.that(resideInAPackage("$pkg.."))

/** The Spring Modulith view of the application, built once and shared. */
internal val APPLICATION_MODULES: ApplicationModules by lazy { ApplicationModules.of(IdentityApplication::class.java) }
