package com.example.identity.core.orchestrator.api.v1

import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.media.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import kotlin.reflect.KClass
import kotlin.reflect.full.memberProperties
import kotlin.reflect.jvm.jvmErasure

/**
 * Marks a DTO property `required` in the OpenAPI schema when it is non-nullable and has no default.
 * swagger-core ignores Kotlin nullability, so generated clients would null-check guaranteed fields.
 * The spec follows the type, with no second annotation to maintain. A default value means "may be
 * omitted", so such a property stays optional.
 */
@Configuration
class KotlinRequiredModelConverterConfig {

    @Bean
    fun kotlinRequiredModelConverter(): ModelConverter = KotlinRequiredModelConverter().also {
        ModelConverters.getInstance().addConverter(it)
        ModelConverters.getInstance(true).addConverter(it)
    }
}

internal class KotlinRequiredModelConverter : ModelConverter {

    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: MutableIterator<ModelConverter>
    ): Schema<*>? {
        val resolved = if (chain.hasNext()) chain.next().resolve(type, context, chain) else null
        val kClass = kotlinClassOf(type) ?: return resolved

        // A $ref-only schema has no properties; the required list belongs to the named schema.
        val target = resolved?.takeIf { it.properties != null }
            ?: resolved?.`$ref`?.let { context.getDefinedModels()[it.substringAfterLast('/')] }
            ?: return resolved

        requiredPropertyNames(kClass)
            .filter { target.properties.containsKey(it) }
            // Idempotent: ModelConverters is JVM-wide, so several Spring contexts in a test run
            // register this converter several times.
            .filter { target.required?.contains(it) != true }
            .forEach { target.addRequiredItem(it) }

        // springdoc also marks non-nullable properties with a default as required. A default
        // means "may be omitted", so they come back out.
        val optional = optionalPropertyNames(kClass)
        target.required?.takeIf { required -> required.any { it in optional } }?.let { required ->
            target.required = required.filterNot { it in optional }.ifEmpty { null }
        }

        return resolved
    }

    private fun optionalPropertyNames(kClass: KClass<*>): Set<String> {
        val primary = runCatching { kClass.constructors.firstOrNull() }.getOrNull() ?: return emptySet()
        return primary.parameters.filter { it.isOptional }.mapNotNull { it.name }.toSet()
    }

    /** Only Kotlin classes with a constructor count as DTOs; anything else stays as resolved. */
    private fun kotlinClassOf(type: AnnotatedType): KClass<*>? {
        val javaType = Json.mapper().constructType(type.type) ?: return null
        // A type documented as another (`@Schema(implementation = ...)`, e.g. Text -> TextRef)
        // takes its required list from that other type.
        val raw = javaType.rawClass.let { declared ->
            declared.getAnnotation(io.swagger.v3.oas.annotations.media.Schema::class.java)?.implementation?.java
                ?.takeIf { it != Void::class.java } ?: declared
        }
        if (raw.getAnnotation(Metadata::class.java) == null) return null
        if (raw.isEnum) return null
        return runCatching { raw.kotlin.takeIf { it.constructors.isNotEmpty() } }.getOrNull()
    }

    private fun requiredPropertyNames(kClass: KClass<*>): List<String> {
        val primary = runCatching { kClass.constructors.firstOrNull() }.getOrNull() ?: return emptyList()
        return runCatching {
            val optionalByName = primary.parameters.associate { it.name to it.isOptional }
            kClass.memberProperties
                .filter { property ->
                    // A property outside the constructor is computed; its nullability decides.
                    val hasDefault = optionalByName[property.name] == true
                    !hasDefault && !property.returnType.isMarkedNullable && property.returnType.jvmErasure != Unit::class
                }
                .map { it.name }
        }.getOrDefault(emptyList())
    }
}
