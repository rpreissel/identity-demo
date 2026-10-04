package com.example.identity.contract.tool_api

import com.example.identity.contract.tool_api.envelope.TOOLS_API

/**
 * A tool in one of its versions (ADR-51). The version belongs to the contract with the client
 * only: claims, `amr` and the offer keep the plain [ToolId]. A client names it in `availableTools`
 * as `enroll-sms@1` and calls it under [path].
 */
data class ToolVersion(val toolId: ToolId, val version: Int) {

    /** Where this version of the tool lives: `/tools/api/enroll-sms/v1`. */
    val path: String get() = "$TOOLS_API/$toolId/v$version"

    override fun toString(): String = "$toolId@$version"

    companion object {
        private val WIRE = Regex("([a-z0-9-]+)@([1-9][0-9]{0,2})")

        /** Reads `<toolId>@<version>`. A name without its version is rejected, there is no default. */
        fun parse(value: String): ToolVersion {
            val match = requireNotNull(WIRE.matchEntire(value)) { "Not of the form <toolId>@<version>" }
            return ToolVersion(ToolId(match.groupValues[1]), match.groupValues[2].toInt())
        }
    }
}
