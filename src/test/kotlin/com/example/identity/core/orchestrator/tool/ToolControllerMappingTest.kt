package com.example.identity.core.orchestrator.tool

import com.example.identity.contract.tool_api.ToolController
import io.kotest.core.spec.style.BehaviorSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.util.ClassUtils
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping

/**
 * A tool controller names its tool once ([ToolController.tool]); its paths name it again, as a
 * constant. This holds the two together against the real Spring context, and checks that every tool
 * of the catalog has exactly one controller (ADR-1).
 */
@SpringBootTest
@ActiveProfiles("test")
class ToolControllerMappingTest(
    toolRegistry: ToolHandlerRegistry,
    controllers: List<ToolController>,
    @Qualifier("requestMappingHandlerMapping") handlerMapping: RequestMappingHandlerMapping,
) : BehaviorSpec({

    given("the tool controllers of the application") {
        then("every tool of the catalog has exactly one") {
            controllers.groupingBy { it.tool.toolId.value }.eachCount() shouldBe
                toolRegistry.tools().associate { it.toolId.value to 1 }
        }
        then("each of their paths names the controller's own tool") {
            val toolByController = controllers.associate { ClassUtils.getUserClass(it) to it.tool.toolId.value }
            val mismatched = handlerMapping.handlerMethods.mapNotNull { (info, method) ->
                val toolId = toolByController[ClassUtils.getUserClass(method.beanType)] ?: return@mapNotNull null
                info.patternValues.filterNot { toolId in it.split('/') }
                    .takeIf { it.isNotEmpty() }
                    ?.let { "${method.beanType.simpleName}.${method.method.name} -> $it (tool $toolId)" }
            }
            mismatched.shouldBeEmpty()
        }
    }
})
