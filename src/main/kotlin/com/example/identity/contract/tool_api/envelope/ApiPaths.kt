package com.example.identity.contract.tool_api.envelope

/**
 * The one place the orchestrator's API version is written down (ADR-51). A new version is a
 * mandatory update: the server serves one at a time, so every tool answers in its envelope.
 */
const val API_V1 = "/orchestrator/api/v1"

/**
 * Where tools live, each in its own versions: `/tools/api/<toolId>/v<N>/…` (ADR-51). Independent of
 * [API_V1], so a new version of one tool touches neither the orchestrator nor the other tools.
 */
const val TOOLS_API = "/tools/api"
