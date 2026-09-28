package com.example.identity.contract.texts

import org.springframework.modulith.ApplicationModule

/**
 * Multilingual texts as a library (ADR-33): [Text], the source wording in the code, and
 * [TextBundle], which serves a bundle's languages by ETag. Knows no journey and no tool, so the
 * simulated foreign systems may depend on it too; a real one would bring its own copy.
 */
@ApplicationModule(id = "texts", allowedDependencies = [])
internal class ModuleMetadata
