package com.example.identity.core.orchestrator.api.v1

import com.example.identity.contract.tool_api.ModuleId
import com.example.identity.contract.tool_api.ModuleRef
import com.example.identity.contract.tool_api.StepDataTypes
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springdoc.core.customizers.OperationCustomizer
import com.example.identity.contract.tool_api.envelope.API_V1
import org.springdoc.core.models.GroupedOpenApi
import org.springframework.beans.factory.FactoryBean
import org.springframework.beans.factory.support.BeanDefinitionBuilder
import org.springframework.beans.factory.support.BeanDefinitionRegistry
import org.springframework.context.ApplicationContext
import org.springframework.context.ApplicationContextAware
import org.springframework.context.EnvironmentAware
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.ImportBeanDefinitionRegistrar
import org.springframework.core.env.Environment
import org.springframework.core.type.AnnotationMetadata
import org.springframework.core.type.filter.AnnotationTypeFilter
import org.springframework.web.bind.annotation.RestController

/**
 * One OpenAPI group per module that has endpoints, so a contract diff names its module (ADR-26).
 * The groups are derived from the existing `@RestController`s, not from a list kept here.
 * [CONTRACT_GROUP] is the published app contract and the generators' input: exactly what lies
 * under [API_V1]. Admin and `/mock-*` endpoints stay out of it, so they never become frozen
 * contract; they still appear in their module's file under `api/modules/`.
 */
@Configuration
@Import(Registrar::class)
class ModuleApiGroups

/**
 * Registers the groups as bean definitions, because the set of modules is only known at runtime.
 * It has to run while configuration classes are parsed: springdoc's group endpoints hang off a
 * `@ConditionalOnBean(GroupedOpenApi)` evaluated then, and springdoc wants one bean per group.
 */
class Registrar : ImportBeanDefinitionRegistrar, EnvironmentAware {

    private lateinit var environment: Environment

    override fun setEnvironment(environment: Environment) {
        this.environment = environment
    }

    override fun registerBeanDefinitions(metadata: AnnotationMetadata, registry: BeanDefinitionRegistry) {
        register(registry, CONTRACT_GROUP, ModuleId.ROOT_PACKAGE, "$API_V1/**")
        modulesWithEndpoints().forEach { module ->
            register(registry, module.id, module.basePackage, null)
        }
    }

    /**
     * Customizers are attached to every group in [ModuleApiGroupFactoryBean]. A group does not
     * inherit customizers registered as plain beans, so one not added there silently never runs.
     */
    private fun register(registry: BeanDefinitionRegistry, group: String, packageToScan: String, pathsToMatch: String?) {
        val definition = BeanDefinitionBuilder
            .genericBeanDefinition(ModuleApiGroupFactoryBean::class.java)
            .addConstructorArgValue(group)
            .addConstructorArgValue(packageToScan)
            .addConstructorArgValue(pathsToMatch)
            .beanDefinition
        registry.registerBeanDefinition("openApiGroup-$group", definition)
    }

    /**
     * Every module with at least one `@RestController` that exists in this environment. Scanned,
     * because `spring-modulith-core` is only a test dependency here. The scanner gets the
     * application's environment, or a `@DemoSurface` controller would count as absent.
     */
    private fun modulesWithEndpoints(): List<ModuleRef> {
        val scanner = ClassPathScanningCandidateComponentProvider(false, environment).apply {
            addIncludeFilter(AnnotationTypeFilter(RestController::class.java))
        }
        return scanner.findCandidateComponents(ModuleId.ROOT_PACKAGE)
            .mapNotNull { it.beanClassName }
            .map { ModuleId.ofPackage(it.substringBeforeLast('.')) }
            .distinct()
            .sortedBy { it.id }
    }
}

/**
 * Builds one [GroupedOpenApi] with every registered customizer. A `FactoryBean`, because a plain
 * supplier gets no access to the bean factory.
 */
class ModuleApiGroupFactoryBean(
    private val group: String,
    private val packageToScan: String,
    /** Null for a module group: it documents everything its module serves. */
    private val pathsToMatch: String?
) : FactoryBean<GroupedOpenApi>, ApplicationContextAware {

    private lateinit var context: ApplicationContext

    override fun setApplicationContext(applicationContext: ApplicationContext) {
        context = applicationContext
    }

    override fun getObjectType(): Class<*> = GroupedOpenApi::class.java

    override fun getObject(): GroupedOpenApi = GroupedOpenApi.builder()
        .group(group)
        .packagesToScan(packageToScan)
        .apply {
            pathsToMatch?.let { pathsToMatch(it) }
            context.getBeanProvider(OperationCustomizer::class.java).forEach { addOperationCustomizer(it) }
            context.getBeanProvider(OpenApiCustomizer::class.java).forEach { addOpenApiCustomizer(it) }
            // Group-aware, so a module's contract lists only the step shapes it can answer with.
            addOpenApiCustomizer(
                context.getBean(StepDataSchemaCustomizer::class.java)
                    .forPackage(packageToScan, context.getBeanProvider(StepDataTypes::class.java).toList())
            )
        }
        .build()
}

/** Shared by the registrar and by `OpenApiSnapshotTest`, which names the contract's file. */
const val CONTRACT_GROUP = "app-vertrag"
