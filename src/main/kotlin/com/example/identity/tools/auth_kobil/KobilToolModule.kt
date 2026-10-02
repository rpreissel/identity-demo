package com.example.identity.tools.auth_kobil

import com.example.identity.contract.tool_api.FactorType.INHERENCE
import com.example.identity.contract.tool_api.FactorType.KNOWLEDGE
import com.example.identity.contract.tool_api.FactorType.POSSESSION
import com.example.identity.contract.tool_api.claims.AcrLevel
import com.example.identity.contract.tool_api.factors
import com.example.identity.contract.tool_api.toolModule
import com.example.identity.contract.tool_api.enroll
import com.example.identity.contract.tool_api.login
import com.example.identity.tools.auth_kobil.api.v1.KobilActivationStep
import com.example.identity.tools.auth_kobil.api.v1.KobilOtpStep
import com.example.identity.tools.auth_kobil.api.v1.KobilUnlockStep
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.modulith.ApplicationModule

internal const val ENROLL_KOBIL_TOOL_ID = "enroll-kobil"
internal const val AUTH_KOBIL_TOOL_ID = "auth-kobil"

/**
 * The KOBIL procedure: `enroll-kobil`, `auth-kobil` (docs/03-tool-architektur.md #1). The credential
 * lives on one phone: the KOBIL activation and the local unlock secret belong to that installation,
 * so it is offered only there, like `device`. One run combines possession of the KOBIL-bound device
 * with the access means (pin = KNOWLEDGE, biometric = INHERENCE); possession is fetched from KOBIL
 * itself, not signed by the client. The access means no server can see; counted under the same
 * reservation as `device` (ADR-21, docs/04-orchestrierung.md #8). `enroll-kobil` starts on the SDK's
 * activation, `auth-kobil` on releasing the PIN.
 */
internal val KobilModule = toolModule(
    method = "kobil",
    proves = factors(POSSESSION, KNOWLEDGE, INHERENCE, upTo = AcrLevel.LOA2),
    demoOnly = "Die KOBIL-Gegenstelle ist simuliert (kobil); was ein echter KOBIL-Server zusagt, steht noch aus",
    onePerDevice = true,
    stepData = listOf(KobilUnlockStep::class, KobilOtpStep::class, KobilActivationStep::class),
    tools = listOf(
        enroll(ENROLL_KOBIL_TOOL_ID, startStep = "activate"),
        login(AUTH_KOBIL_TOOL_ID, startStep = "unlock"),
    ),
)

/**
 * A method module talks to the orchestrator through tool_api only (docs/03-tool-architektur.md #2).
 * The extra edge to `kobil` is the point of the module: a KOBIL credential is not verified here,
 * KOBIL asserts it and we redeem it. The declared dependency makes that visible in the module graph.
 */
@ApplicationModule(id = "auth_kobil", allowedDependencies = ["tool_api", "kobil", "texts"])
@Configuration
internal class KobilToolModule {
    @Bean
    fun kobilModule() = KobilModule
}
