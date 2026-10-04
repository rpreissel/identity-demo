package com.example.identity.core.orchestrator.api.v1

import com.example.identity.contract.tool_api.AuthorizedToolContext
import com.example.identity.contract.tool_api.ActivationToolContext
import com.example.identity.contract.tool_api.ToolContext
import com.example.identity.contract.tool_api.ToolController
import com.example.identity.contract.tool_api.ToolJourney
import com.example.identity.contract.tool_api.ToolVersion
import com.example.identity.contract.tool_api.envelope.TOOLS_API
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
 * [ActivationToolContext] activates the tool on the channel the query names (`?channel=`), an
 * [AuthorizedToolContext] loads the session in the path and requires it to be the journey's current
 * tool, a plain [ToolContext] only loads it. So a handler that may change the journey cannot get an
 * unverified session. The version is the one the route names (`/tools/api/<toolId>/v<N>`, ADR-51);
 * a route of another tool, or of a version the tool does not declare, is a programming error.
 */
@Component
class ToolContextResolver(
    private val bindingKeyResolver: DpopBindingKeyResolver,
    @Lazy private val toolJourney: ToolJourney,
    private val applicationContext: ApplicationContext,
) : HandlerMethodArgumentResolver {

    private val toolByRoute = ConcurrentHashMap<Pair<Class<*>, String>, ToolVersion>()

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
        val tool = toolOf(parameter, request)
        if (ActivationToolContext::class.java.isAssignableFrom(parameter.parameterType)) {
            val raw = requireNotNull(request.getParameter(CHANNEL_QUERY_PARAMETER)) { "The channel to activate the tool on is missing" }
            val channelSessionId = ChannelSessionId(uuid(raw, parameter, CHANNEL_QUERY_PARAMETER, ChannelSessionId::class.java))
            return toolJourney.beginActivation(channelSessionId, bindingKeyResolver.bindingKeyOf(request), tool)
        }
        @Suppress("UNCHECKED_CAST")
        val pathVariables = request.getAttribute(HandlerMapping.URI_TEMPLATE_VARIABLES_ATTRIBUTE) as? Map<String, String>
        val raw = checkNotNull(pathVariables?.get(PATH_VARIABLE)) { "The tool context parameter needs a {$PATH_VARIABLE} in the path" }
        val toolSessionId = ToolSessionId(uuid(raw, parameter, PATH_VARIABLE, ToolSessionId::class.java))
        val bindingKeyRef = bindingKeyResolver.bindingKeyOf(request)
        return if (AuthorizedToolContext::class.java.isAssignableFrom(parameter.parameterType)) {
            toolJourney.loadCurrent(toolSessionId, bindingKeyRef, tool)
        } else {
            toolJourney.loadContext(toolSessionId, bindingKeyRef, tool)
        }
    }

    /** The tool and version the matched route serves, checked once per controller and route. */
    private fun toolOf(parameter: MethodParameter, request: HttpServletRequest): ToolVersion {
        val route = checkNotNull(request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE) as? String) { "No matched route" }
        return toolByRoute.computeIfAbsent(parameter.containingClass to route) { (type, pattern) ->
            val controller = applicationContext.getBean(type)
            check(controller is ToolController) {
                "${type.name} takes a ToolContext but is no ToolController: it must name the tool it serves"
            }
            val (toolId, version) = checkNotNull(ROUTE.find(pattern)) { "$pattern is no tool route under $TOOLS_API/<toolId>/v<N>" }.destructured
            check(toolId == controller.tool.toolId.value) { "$pattern serves $toolId, but ${type.name} is the controller of ${controller.tool}" }
            checkNotNull(controller.tool.inVersion(version.toInt())) { "$pattern serves version $version, which ${controller.tool} does not declare" }
        }
    }

    /** A malformed id is answered like a mistyped @PathVariable (400, naming only the parameter). */
    private fun uuid(raw: String, parameter: MethodParameter, name: String, type: Class<*>): UUID =
        try {
            UUID.fromString(raw)
        } catch (e: IllegalArgumentException) {
            throw MethodArgumentTypeMismatchException(raw, type, name, parameter, e)
        }

    companion object {
        const val PATH_VARIABLE = "toolSessionId"

        /** How an activation names its channel: the body belongs to the tool. */
        const val CHANNEL_QUERY_PARAMETER = "channel"

        private val ROUTE = Regex("^${Regex.escape(TOOLS_API)}/([^/{]+)/v([0-9]+)(/|$)")
    }
}
