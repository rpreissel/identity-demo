package com.example.identity.core.orchestrator.api.v1

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverter
import io.swagger.v3.core.converter.ModelConverterContext
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.core.util.Json
import io.swagger.v3.oas.models.media.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Fills in the `discriminator.mapping` of a Jackson-polymorphic DTO from its `@JsonSubTypes`.
 * swagger-core emits only the property name. Without the mapping, generators fall back to schema
 * names and would send a `kind` value Jackson rejects. Derived rather than declared with
 * `@Schema(discriminatorMapping = ...)`, so the spec follows the declaration.
 */
@Configuration
class JacksonSubTypesModelConverterConfig {

    @Bean
    fun jacksonSubTypesModelConverter(): ModelConverter = JacksonSubTypesModelConverter().also {
        ModelConverters.getInstance().addConverter(it)
        ModelConverters.getInstance(true).addConverter(it)
    }
}

internal class JacksonSubTypesModelConverter : ModelConverter {

    override fun resolve(
        type: AnnotatedType,
        context: ModelConverterContext,
        chain: MutableIterator<ModelConverter>
    ): Schema<*>? {
        val resolved = if (chain.hasNext()) chain.next().resolve(type, context, chain) else null
        val raw = runCatching { Json.mapper().constructType(type.type)?.rawClass }.getOrNull()
            ?: return resolved

        val typeInfo = raw.getAnnotation(JsonTypeInfo::class.java) ?: return resolved
        if (typeInfo.use != JsonTypeInfo.Id.NAME) return resolved
        val subTypes = raw.getAnnotation(JsonSubTypes::class.java)?.value ?: return resolved

        val target = schemaCarrying(resolved, context) ?: return resolved
        val discriminator = target.discriminator ?: return resolved

        subTypes.forEach { subType ->
            val name = subType.name.takeIf { it.isNotEmpty() } ?: return@forEach
            if (discriminator.mapping?.containsKey(name) == true) return@forEach
            // Resolve the subtype first, so the mapping does not point at a missing schema.
            val subSchema = context.resolve(AnnotatedType(subType.value.java))
            val schemaName = subSchema?.`$ref`?.substringAfterLast('/')
                ?: subType.value.java.simpleName
            discriminator.mapping(name, "#/components/schemas/$schemaName")
        }
        return resolved
    }

    /** The schema carrying the discriminator: for a `$ref`, the named model behind it. */
    private fun schemaCarrying(resolved: Schema<*>?, context: ModelConverterContext): Schema<*>? =
        resolved?.takeIf { it.discriminator != null }
            ?: resolved?.`$ref`?.let { context.getDefinedModels()[it.substringAfterLast('/')] }
}
