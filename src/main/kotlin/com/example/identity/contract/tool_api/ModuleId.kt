package com.example.identity.contract.tool_api

import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.core.io.DefaultResourceLoader
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.modulith.ApplicationModule

/** A module as its [ApplicationModule] declares it: the id and the package it starts at. */
data class ModuleRef(val id: String, val basePackage: String)

/**
 * The one way to tell which module a class belongs to. Reads the same `@ApplicationModule.id` that
 * Spring Modulith verifies, so the id in a schema, an API group, a budget namespace or a text
 * bundle cannot drift from the module structure (docs/08-projektrahmen.md M-1).
 */
object ModuleId {
    /** The package every module lives under. */
    const val ROOT_PACKAGE = "com.example.identity"

    /**
     * Every module by its package: the classes under [ROOT_PACKAGE] that carry `@ApplicationModule`,
     * whatever they are called. Read once from the class files, without loading a class.
     */
    private val modulesByPackage: Map<String, ModuleRef> by lazy {
        val scanner = ClassPathScanningCandidateComponentProvider(false).apply {
            addIncludeFilter(AnnotationTypeFilter(ApplicationModule::class.java, false))
            setResourceLoader(DefaultResourceLoader(ModuleId::class.java.classLoader))
        }
        scanner.findCandidateComponents(ROOT_PACKAGE).map { candidate ->
            val metadata = (candidate as AnnotatedBeanDefinition).metadata
            val pkg = metadata.className.substringBeforeLast('.')
            val id = metadata.getAnnotationAttributes(ApplicationModule::class.java.name)?.get("id") as String?
            ModuleRef(id.orEmpty().ifEmpty { pkg.substringAfterLast('.') }, pkg)
        }.groupBy { it.basePackage }.mapValues { (pkg, refs) ->
            refs.singleOrNull() ?: error("Package $pkg declares more than one @ApplicationModule: ${refs.map { it.id }}")
        }
    }

    fun of(type: Class<*>): ModuleRef = ofPackage(type.packageName)

    /** Walks up from [pkg] to the nearest package that declares a module with `@ApplicationModule`. */
    fun ofPackage(pkg: String): ModuleRef =
        find(pkg) ?: error("No module above $pkg: every module declares itself in a class with @ApplicationModule")

    /** Like [ofPackage], but `null` for a package outside every module (the application class). */
    fun find(pkg: String): ModuleRef? {
        var candidate = pkg
        while (candidate.isNotEmpty()) {
            modulesByPackage[candidate]?.let { return it }
            candidate = candidate.substringBeforeLast('.', "")
        }
        return null
    }
}
