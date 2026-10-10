package com.example.identity.contract.tool_api

/** A tool's current step as its flow describes it: the step's name and what the client needs to see. */
data class ToolStep(val name: String, val data: StepData? = null) {
    fun inProgress(demo: Map<String, Any?>? = null): ToolOutcome.InProgress =
        ToolOutcome.InProgress(nextStep = name, stepData = data, demo = demo)
}
