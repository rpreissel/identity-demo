package com.example.identity.contract.tool_api

/**
 * Marks a controller parameter that receives the context of the tool session in the path
 * (`{toolSessionId}`), loaded for [toolId] and checked against the caller's binding key before the
 * method runs. The parameter's type says what the method may do: a [ToolContext] reads
 * ([ToolJourney.loadContext]), an [AuthorizedToolContext] may change the journey and must be the
 * journey's current tool ([ToolJourney.loadCurrent]).
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class LoadTool(val toolId: String)

/**
 * Marks a controller parameter that receives the context of a freshly activated [toolId]: before
 * the method runs, the tool is activated on the channel in the path (`{channelSessionId}`) for the
 * caller's binding key, which creates its tool session and advances the journey
 * ([ToolJourney.beginActivation]). The method then only starts its handler. Parameters are resolved
 * in order: declare it after a `@RequestBody`, so an unreadable request activates nothing.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.RUNTIME)
annotation class ActivateTool(val toolId: String)
