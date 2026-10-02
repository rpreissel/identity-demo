package com.example.identity.core.orchestrator.api.v1

import com.example.identity.contract.tool_api.AuthorizedToolContext
import com.example.identity.contract.tool_api.ActivateTool
import com.example.identity.contract.tool_api.LoadTool
import com.example.identity.contract.tool_api.ToolContext
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.contract.tool_api.ids.ToolSessionId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.annotation.Lazy
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer
import org.springframework.web.servlet.HandlerMapping
import java.util.UUID

/**
 * Resolves a `@LoadTool` or `@ActivateTool` controller parameter: the context of the tool session
 * in the path, or of the tool just activated on the channel in the path, bound to the caller's key
 * like `@BindingKey` ([DpopBindingKeyResolver]). For `@LoadTool` the parameter's type picks the
 * read or the write path, so a handler that may change the journey cannot get an unverified session
 * (docs/03-tool-architektur.md #2).
 */
@Component
class ToolContextResolver(
    private val bindingKeyResolver: DpopBindingKeyResolver,
    @Lazy private val toolJourney: ToolJourney,
) : HandlerMethodArgumentResolver {

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.hasParameterAnnotation(LoadTool::class.java) || parameter.hasParameterAnnotation(ActivateTool::class.java)

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?
    ): ToolContext {
        val request = checkNotNull(webRequest.getNativeRequest(HttpServletRequest::class.java)) {
            "Tool context resolution requires a servlet request"
        }
        // Same order as a @PathVariable followed by @BindingKey: a malformed id is refused first.
        parameter.getParameterAnnotation(ActivateTool::class.java)?.let { activate ->
            val channelSessionId = ChannelSessionId(pathId(request, parameter, CHANNEL_PATH_VARIABLE, ChannelSessionId::class.java))
            return toolJourney.beginActivation(channelSessionId, bindingKeyResolver.bindingKeyOf(request), activate.toolId)
        }
        val toolId = checkNotNull(parameter.getParameterAnnotation(LoadTool::class.java)).toolId
        val toolSessionId = ToolSessionId(pathId(request, parameter, PATH_VARIABLE, ToolSessionId::class.java))
        val bindingKeyRef = bindingKeyResolver.bindingKeyOf(request)
        return if (AuthorizedToolContext::class.java.isAssignableFrom(parameter.parameterType)) {
            toolJourney.loadCurrent(toolSessionId, bindingKeyRef, toolId)
        } else {
            toolJourney.loadContext(toolSessionId, bindingKeyRef, toolId)
        }
    }

    /** A malformed id is answered like a mistyped @PathVariable (400, naming only the parameter). */
    private fun pathId(request: HttpServletRequest, parameter: MethodParameter, name: String, type: Class<*>): UUID {
        @Suppress("UNCHECKED_CAST")
        val pathVariables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>
        val raw = checkNotNull(pathVariables?.get(name)) { "The tool context parameter needs a {$name} in the path" }
        return try {
            UUID.fromString(raw)
        } catch (e: IllegalArgumentException) {
            throw MethodArgumentTypeMismatchException(raw, type, name, parameter, e)
        }
    }

    companion object {
        const val PATH_VARIABLE = "toolSessionId"
        const val CHANNEL_PATH_VARIABLE = "channelSessionId"
    }
}
