package com.example.identity.contract.tool_api

import org.springframework.modulith.ApplicationModule

/** A module as its [ModuleMetadata][ApplicationModule] declares it: the id and the package it starts at. */
data class ModuleRef(val id: String, val basePackage: String)

/**
 * The one way to tell which module a class belongs to. Reads the same `@ApplicationModule.id` that
 * Spring Modulith verifies, so the id in a schema, an API group, a budget namespace or a text
 * bundle cannot drift from the module structure (docs/08-projektrahmen.md M-1).
 */
object ModuleId {
    /** The package every module lives under. */
    const val ROOT_PACKAGE = "com.example.identity"

    fun of(type: Class<*>): ModuleRef = ofPackage(type.packageName, type.classLoader)

    /**
     * Walks up from [pkg] to the first `ModuleMetadata` that carries `@ApplicationModule`. A class
     * of that name without the annotation (a package marker inside a module) does not count.
     */
    fun ofPackage(pkg: String, loader: ClassLoader = ModuleId::class.java.classLoader): ModuleRef =
        find(pkg, loader)
            ?: error("No module above $pkg: every module declares itself in a ModuleMetadata with @ApplicationModule")

    /** Like [ofPackage], but `null` for a package outside every module (the application class). */
    fun find(pkg: String, loader: ClassLoader = ModuleId::class.java.classLoader): ModuleRef? {
        var candidate = pkg
        while (candidate.isNotEmpty()) {
            val module = runCatching { Class.forName("$candidate.ModuleMetadata", false, loader) }.getOrNull()
                ?.getAnnotation(ApplicationModule::class.java)
            if (module != null) return ModuleRef(module.id.ifEmpty { candidate.substringAfterLast('.') }, candidate)
            candidate = candidate.substringBeforeLast('.', "")
        }
        return null
    }
}
