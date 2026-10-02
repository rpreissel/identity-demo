package com.example.identity.architecture

import com.tngtech.archunit.core.domain.JavaClasses
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption

/** The application's classes without the tests, imported once and shared by the ArchUnit specs. */
internal val MAIN_CLASSES: JavaClasses by lazy {
    ClassFileImporter()
        .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
        .importPackages("com.example.identity")
}
