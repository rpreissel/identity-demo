package com.example.identity.core.orchestrator.api.v1.tool

import com.example.identity.contract.texts.Text
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
    @field:Schema(example = "KNOWN_ACCOUNT_AUTH") val role: String,
    /** The versions of the tool's contract the server serves, ascending (ADR-51). A client names one in `availableTools`. */
    @field:Schema(example = "[1]") val versions: List<Int>,
    /** What users call the tool - its method's name unless the tool names itself. */
    val name: Text,
    /** What it does, in a few words, under its name in a selection. */
    val hint: Text,
)

/**
 * The public, read-only tool catalog: which tools in which versions a client may declare as
 * `availableTools`, what the admin UI can toggle, and what each tool is called. The app and the
 * login pages take names and hints from here (as text references in the app bundle), so a tool is
 * named in one place: its module (docs/03-tool-architektur.md #2).
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
                      {"toolId": "ident-fsc", "method": "fsc", "role": "IDENTIFICATION", "versions": [1], "name": {"key": "freischaltcode-1b35c2"}, "hint": {"key": "persoenliche-daten-und-freischaltcode-8a654d"}},
                      {"toolId": "enroll-sms", "method": "sms", "role": "ENROLLMENT", "versions": [1], "name": {"key": "sms-40b601"}, "hint": {"key": "code-an-eine-telefonnummer-a62b47"}},
                      {"toolId": "auth-sms", "method": "sms", "role": "KNOWN_ACCOUNT_AUTH", "versions": [1], "name": {"key": "sms-40b601"}, "hint": {"key": "code-an-die-hinterlegte-telefonnummer-76a2d3"}}
                    ]
                """)])]
            )
        ]
    )
    fun catalog(): List<ToolCatalogEntry> =
        toolRegistry.tools().map { ToolCatalogEntry(it.toolId.value, it.method, it.role.name, it.versions.toList(), it.name, it.hint) }
}
