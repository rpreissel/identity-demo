package com.example.identity.core.orchestrator.tool

import com.example.identity.contract.tool_api.ToolController
import com.example.identity.core.orchestrator.domain.journey.strategy.StrategyTestFixtures
import io.kotest.assertions.withClue
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
 * constant. This holds the two together against the real Spring context, checks that every tool
 * of the catalog has exactly one controller (ADR-1), and that the integration tests' pinned catalog
 * knows every module.
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
        then("the integration tests pin every module, so a new one does not fail there as 'Unknown tool'") {
            val unpinned = toolRegistry.modules().map { it.method } - StrategyTestFixtures.modules.map { it.method }.toSet()
            withClue(
                "Add the module(s) $unpinned to StrategyTestFixtures.modules. The integration tests run against " +
                    "that list (PinnedToolCatalogTestConfig), so adding one may change expected candidate lists there."
            ) { unpinned.shouldBeEmpty() }
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
