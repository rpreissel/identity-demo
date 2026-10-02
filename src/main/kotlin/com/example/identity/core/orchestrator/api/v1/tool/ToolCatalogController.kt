package com.example.identity.core.orchestrator.api.v1.tool

import com.example.identity.core.orchestrator.tool.ToolHandlerRegistry
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.ExampleObject
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import com.example.identity.contract.tool_api.envelope.API_V1

data class ToolCatalogEntry(
    @field:Schema(example = "auth-sms") val toolId: String,
    @field:Schema(example = "sms") val method: String,
    @field:Schema(example = "KNOWN_ACCOUNT_AUTH") val role: String
)

/**
 * The public, read-only tool catalog: which toolIds a client may declare as `availableTools`, and
 * what the admin UI can toggle.
 */
@RestController
@RequestMapping("$API_V1/tools")
@Tag(name = "Tool catalog", description = "The full set of registered tools, independent of any journey")
class ToolCatalogController(private val toolRegistry: ToolHandlerRegistry) {

    @GetMapping("/catalog")
    @Operation(
        summary = "List every registered tool",
        description = "No auth, no channel required - purely descriptive.",
        responses = [
            ApiResponse(
                responseCode = "200",
                content = [Content(mediaType = "application/json", array = ArraySchema(schema = Schema(implementation = ToolCatalogEntry::class)), examples = [ExampleObject(value = """
                    [
                      {"toolId": "ident-fsc", "method": "fsc", "role": "IDENTIFICATION"},
                      {"toolId": "enroll-sms", "method": "sms", "role": "ENROLLMENT"},
                      {"toolId": "auth-sms", "method": "sms", "role": "KNOWN_ACCOUNT_AUTH"},
                      {"toolId": "auth-sms-lookup", "method": "sms", "role": "ACCOUNT_LOOKUP_AUTH"}
                    ]
                """)])]
            )
        ]
    )
    fun catalog(): List<ToolCatalogEntry> =
        toolRegistry.tools().map { ToolCatalogEntry(it.toolId.value, it.method, it.role.name) }
}
