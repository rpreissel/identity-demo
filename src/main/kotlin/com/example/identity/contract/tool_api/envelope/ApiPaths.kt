package com.example.identity.contract.tool_api.envelope

/**
 * The one place the API version is written down (docs/05-api.md). It does not make two versions
 * servable side by side: the response envelope is shared, so a breaking change is global anyway.
 */
const val API_V1 = "/orchestrator/api/v1"
