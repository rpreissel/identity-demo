package com.example.identity.core.orchestrator.api.v1

import io.swagger.v3.oas.models.media.IntegerSchema
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.media.StringSchema
import io.swagger.v3.oas.models.media.UUIDSchema
import org.springdoc.core.customizers.OperationCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.method.HandlerMethod
import java.util.UUID
import kotlin.reflect.KClass
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.kotlinFunction

/**
 * Handler methods take ids as value classes (`ChannelSessionId`, `PartnerNumber`); on the wire they
 * are the bare value. springdoc sees neither through the class nor past the JVM name Kotlin gives
 * such a method (`activate-Ab3dE_f`). This keeps the contract as the code reads.
 */
@Configuration
class ValueClassOpenApiConfig {

    @Bean
    fun valueClassOperationCustomizer(): OperationCustomizer = OperationCustomizer { operation, handlerMethod ->
        operation.operationId = operation.operationId?.replace(MANGLED, "$1$2")
        val valueTypes = valueClassParameters(handlerMethod)
        operation.parameters?.forEach { parameter ->
            valueTypes[parameter.name]?.let { parameter.schema = it }
        }
        operation
    }

    /** Parameter name to the schema of its value, for each value class parameter; a fresh schema each time. */
    private fun valueClassParameters(handlerMethod: HandlerMethod): Map<String, Schema<*>> =
        handlerMethod.method.kotlinFunction?.parameters.orEmpty()
            .mapNotNull { parameter ->
                val type = parameter.type.classifier as? KClass<*> ?: return@mapNotNull null
                if (!type.isValue) return@mapNotNull null
                val name = parameter.name ?: return@mapNotNull null
                schemaOf(type.primaryConstructor?.parameters?.singleOrNull()?.type?.classifier)?.let { name to it }
            }
            .toMap()

    private fun schemaOf(valueType: Any?): Schema<*>? = when (valueType) {
        String::class -> StringSchema()
        UUID::class -> UUIDSchema()
        Long::class -> IntegerSchema().format("int64")
        else -> null
    }

    private companion object {
        /** Kotlin's mangling suffix: a dash and seven characters, before springdoc's `_n` for duplicates. */
        val MANGLED = Regex("^([^-]+)-[A-Za-z0-9_-]{7}(_\\d+)?$")
    }
}
