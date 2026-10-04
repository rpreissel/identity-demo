package com.example.identity.core.orchestrator.tool

import com.example.identity.contract.tool_api.ToolController
import com.example.identity.contract.tool_api.envelope.TOOLS_API
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
 * A tool controller names its tool once ([ToolController.tool]); its paths name it again, with the
 * version it serves (`/tools/api/<toolId>/v<N>`, ADR-51). This holds the two together against the
 * real Spring context, checks that every version a tool declares has exactly one controller and no
 * controller serves an undeclared one (ADR-1), and that the integration tests' pinned catalog knows
 * every module.
 */
@SpringBootTest
@ActiveProfiles("test")
class ToolControllerMappingTest(
    toolRegistry: ToolHandlerRegistry,
    controllers: List<ToolController>,
    @Qualifier("requestMappingHandlerMapping") handlerMapping: RequestMappingHandlerMapping,
) : BehaviorSpec({

    val pathsByController: Map<Class<*>, List<String>> = handlerMapping.handlerMethods.entries
        .groupBy({ ClassUtils.getUserClass(it.value.beanType) }, { it.key.patternValues })
        .mapValues { (_, patterns) -> patterns.flatten() }

    given("the tool controllers of the application") {
        then("every version a tool declares has exactly one, and none serves an undeclared version") {
            val served = controllers.map { controller ->
                val versions = pathsByController[ClassUtils.getUserClass(controller)].orEmpty()
                    .mapNotNull { VERSION.find(it)?.groupValues?.get(1)?.toInt() }.distinct()
                withClue("${controller::class.simpleName} must serve exactly one version, it serves $versions") {
                    versions.size shouldBe 1
                }
                controller.tool.toolId.value to versions.single()
            }
            served.groupingBy { it }.eachCount() shouldBe
                toolRegistry.tools().flatMap { tool -> tool.versions.map { tool.toolId.value to it } }.associateWith { 1 }
        }
        then("the integration tests pin every module, so a new one does not fail there as 'Unknown tool'") {
            val unpinned = toolRegistry.modules().map { it.method } - StrategyTestFixtures.modules.map { it.method }.toSet()
            withClue(
                "Add the module(s) $unpinned to StrategyTestFixtures.modules. The integration tests run against " +
                    "that list (PinnedToolCatalogTestConfig), so adding one may change expected candidate lists there."
            ) { unpinned.shouldBeEmpty() }
        }
        then("each of their paths lies under the controller's own tool") {
            val mismatched = controllers.flatMap { controller ->
                val toolId = controller.tool.toolId.value
                pathsByController[ClassUtils.getUserClass(controller)].orEmpty()
                    .filterNot { it.startsWith("$TOOLS_API/$toolId/v") }
                    .map { "${controller::class.simpleName} -> $it (tool $toolId)" }
            }
            mismatched.shouldBeEmpty()
        }
    }
}) {
    private companion object {
        val VERSION = Regex("^${Regex.escape(TOOLS_API)}/[^/]+/v([0-9]+)(/|$)")
    }
}
