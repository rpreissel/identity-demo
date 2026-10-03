package com.example.identity.tools.ident_nect.api.v1

import com.example.identity.contract.tool_api.StepData
import com.example.identity.contract.tool_api.stepData
import java.util.UUID

/** The step shapes `ident_nect` produces. */
internal val NectStepData = mapOf(
    "nect-redirect" to stepData<NectRedirectStep>(
        "The user leaves for Nect's jump page; the app comes back with ?nectCaseId=... and reports it.",
        "jumpUrl" to "/nect/?case=5b1c2d3e-0000-4000-8000-000000000001",
    ),
)

/** Where to send the user to identify, and which case the return belongs to. */
data class NectRedirectStep(
    val jumpUrl: String,
    val caseId: UUID
) : StepData
