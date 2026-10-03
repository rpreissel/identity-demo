package com.example.identity.core.orchestrator.api.v1

import com.example.identity.contract.tool_api.ModuleId
import com.example.identity.contract.tool_api.StepData
import com.example.identity.contract.tool_api.StepDataShape
import com.example.identity.contract.tool_api.StepDataTypes
import io.swagger.v3.core.converter.AnnotatedType
import io.swagger.v3.core.converter.ModelConverters
import io.swagger.v3.oas.models.media.Discriminator
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Configuration

/**
 * Puts every declared [StepData] shape into the API description. The shapes travel through
 * `ToolOutcome`, never a controller signature, so springdoc cannot reach them on its own. The list
 * comes from the modules' declarations ([StepDataShape]: tool modules and [StepDataTypes] beans),
 * not from a constant here; each brings its description and examples, the shapes carry no
 * annotations.
 */
@Configuration
class StepDataSchemaCustomizer {

    /**
     * One customizer per group, because a group lists only the shapes its own endpoints can answer
     * with. The SMS module's contract must not carry KOBIL's shapes.
     */
    fun forPackage(scannedPackage: String, declarations: List<StepDataTypes>): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        val shapes = declarations.flatMap { it.types().entries }
            .distinctBy { it.key }
            .filter { belongsTo(it.value.type.java, scannedPackage) }
        if (shapes.isEmpty()) return@OpenApiCustomizer

        val components = openApi.components ?: return@OpenApiCustomizer
        val base = components.schemas?.get(STEP_DATA_SCHEMA) ?: return@OpenApiCustomizer

        val discriminator = Discriminator().propertyName(DISCRIMINATOR)
        shapes.sortedBy { it.value.type.simpleName }.forEach { (kind, shape) ->
            val java = shape.type.java
            // Resolve into the document first, so the $ref below does not dangle.
            ModelConverters.getInstance().readAll(java).forEach { (name, schema) ->
                components.schemas.putIfAbsent(name, schema)
            }
            val name = ModelConverters.getInstance().read(AnnotatedType(java)).keys.firstOrNull()
                ?: java.simpleName
            components.schemas[name]?.let {
                declareKind(it, kind)
                describe(it, shape)
            }
            base.addOneOfItem(Schema<Any>().`$ref`("#/components/schemas/$name"))
            discriminator.mapping(kind, "#/components/schemas/$name")
        }
        base.discriminator = discriminator
        // The union carries only the discriminator; `kind` lives in every shape. Otherwise
        // generators see two conflicting statements and warn.
        base.properties = null
        base.required = null
        base.types = null
        base.type = null
    }

    /**
     * `kind` belongs to the shape, because Jackson writes it into every subtype object. A
     * single-value enum, so a client can narrow on the field without looking up the mapping.
     */
    private fun declareKind(shape: Schema<*>, kind: String) {
        if (shape.properties?.containsKey(DISCRIMINATOR) == true) return
        val property = Schema<String>().apply {
            types = setOf("string")
            type = "string"
            enum = listOf(kind)
        }
        shape.properties = linkedMapOf<String, Schema<*>>(DISCRIMINATOR to property) + (shape.properties ?: emptyMap())
        shape.required = listOf(DISCRIMINATOR) + (shape.required ?: emptyList()).filterNot { it == DISCRIMINATOR }
    }

    /** What the module declared about the shape: its description and the example per property. */
    private fun describe(schema: Schema<*>, shape: StepDataShape) {
        schema.description = shape.description
        shape.examples.forEach { (property, example) ->
            val target = checkNotNull(schema.properties?.get(property)) {
                "${shape.type.simpleName} has no property '$property' for its declared example"
            }
            target.example = example
        }
    }

    /**
     * Its own module's shapes, plus the shared ones in `tool_api` and the orchestrator's screens,
     * which any tool endpoint may answer with when the journey moves on.
     */
    private fun belongsTo(java: Class<*>, scannedPackage: String): Boolean {
        val pkg = java.packageName
        return pkg.startsWith(scannedPackage) ||
            pkg.startsWith(SHARED_PACKAGE) ||
            pkg.startsWith(ORCHESTRATOR_PACKAGE)
    }

    private companion object {
        private const val STEP_DATA_SCHEMA = "StepData"

        /** The property [StepDataWireFormat] writes, so the spec names what goes on the wire. */
        private const val DISCRIMINATOR = StepDataWireFormat.KIND
        private val SHARED_PACKAGE = ModuleId.of(StepData::class.java).basePackage
        private val ORCHESTRATOR_PACKAGE = ModuleId.of(StepDataSchemaCustomizer::class.java).basePackage
    }
}
