package com.example.identity.core.orchestrator.api.v1

import com.example.identity.contract.tool_api.AuthorizedToolContext
import com.example.identity.contract.tool_api.ActivationToolContext
import com.example.identity.contract.tool_api.ToolContext
import com.example.identity.contract.tool_api.ToolController
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ids.ChannelSessionId
import com.example.identity.contract.tool_api.ids.ToolSessionId
import jakarta.servlet.http.HttpServletRequest
import org.springframework.context.ApplicationContext
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
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves a [ToolContext] controller parameter for the controller's tool ([ToolController.tool]),
 * bound to the caller's key like `@BindingKey` ([DpopBindingKeyResolver]). The parameter's type
 * says what happens before the method runs (docs/03-tool-architektur.md #2): an
 * [ActivationToolContext] activates the tool on the channel in the path, an [AuthorizedToolContext]
 * loads the session in the path and requires it to be the journey's current tool, a plain
 * [ToolContext] only loads it. So a handler that may change the journey cannot get an unverified
 * session, and the path and the context cannot name different tools.
 */
@Component
class ToolContextResolver(
    private val bindingKeyResolver: DpopBindingKeyResolver,
    @Lazy private val toolJourney: ToolJourney,
    private val applicationContext: ApplicationContext,
) : HandlerMethodArgumentResolver {

    private val toolIdByController = ConcurrentHashMap<Class<*>, String>()

    override fun supportsParameter(parameter: MethodParameter): Boolean =
        ToolContext::class.java.isAssignableFrom(parameter.parameterType)

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
        val toolId = toolIdOf(parameter)
        if (ActivationToolContext::class.java.isAssignableFrom(parameter.parameterType)) {
            val channelSessionId = ChannelSessionId(pathId(request, parameter, CHANNEL_PATH_VARIABLE, ChannelSessionId::class.java))
            return toolJourney.beginActivation(channelSessionId, bindingKeyResolver.bindingKeyOf(request), toolId)
        }
        val toolSessionId = ToolSessionId(pathId(request, parameter, PATH_VARIABLE, ToolSessionId::class.java))
        val bindingKeyRef = bindingKeyResolver.bindingKeyOf(request)
        return if (AuthorizedToolContext::class.java.isAssignableFrom(parameter.parameterType)) {
            toolJourney.loadCurrent(toolSessionId, bindingKeyRef, toolId)
        } else {
            toolJourney.loadContext(toolSessionId, bindingKeyRef, toolId)
        }
    }

    /** The id of the tool the method's controller serves, read once per controller class. */
    private fun toolIdOf(parameter: MethodParameter): String =
        toolIdByController.computeIfAbsent(parameter.containingClass) { type ->
            val controller = applicationContext.getBean(type)
            check(controller is ToolController) {
                "${type.name} takes a ToolContext but is no ToolController: it must name the tool it serves"
            }
            controller.tool.toolId.value
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
