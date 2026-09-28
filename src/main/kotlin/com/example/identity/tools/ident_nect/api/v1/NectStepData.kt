package com.example.identity.tools.ident_nect.api.v1

import com.example.identity.contract.tool_api.StepData
import com.example.identity.contract.tool_api.StepDataTypes
import com.fasterxml.jackson.annotation.JsonTypeName
import io.swagger.v3.oas.annotations.media.Schema
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.UUID

/** Where to send the user to identify, and which case the return belongs to. */
@JsonTypeName("nect-redirect")
@Schema(description = "The user leaves for Nect's jump page; the app comes back with ?nectCaseId=... and reports it.")
data class NectRedirectStep(
    @field:Schema(example = "/nect/?case=5b1c2d3e-0000-4000-8000-000000000001")
    val jumpUrl: String,
    val caseId: UUID
) : StepData

@Configuration
class NectStepDataTypes {

    @Bean
    fun nectStepDataShapes() = StepDataTypes { listOf(NectRedirectStep::class) }
}
