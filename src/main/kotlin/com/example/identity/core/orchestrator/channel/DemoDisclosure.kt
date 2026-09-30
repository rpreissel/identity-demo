package com.example.identity.core.orchestrator.channel

import com.example.identity.contract.tool_api.ids.AccountId
import com.example.identity.contract.tool_api.values.PartnerNumber
import com.example.identity.demo.demo_mode.OutsideDemoMode
import com.example.identity.demo.demo_mode.OnlyInDemoMode
import com.example.identity.contract.tool_api.envelope.DemoInfo
import com.example.identity.contract.tool_api.envelope.DemoSession
import com.example.identity.contract.tool_api.envelope.JourneyDebugStep
import org.springframework.stereotype.Component

/**
 * The only thing that may put demo-only values into a response (ADR-28). The `demo` block carries
 * plaintext TANs, codes, the demo password and every persona's data, so a deployment must be able
 * to switch it off. `demo.mode` decides at startup which implementation exists; with it off no
 * code path can build the block. `OrchestratorArchitectureTest` keeps this the only construction site.
 */
interface DemoDisclosure {

    /**
     * @param values what the tool that just ran attached as
     *   [com.example.identity.contract.tool_api.ToolOutcome.InProgress.demo], or null.
     * @return the block for the response, or `null` when there is nothing that may be said.
     */
    fun assemble(
        accountId: AccountId?,
        personId: PartnerNumber?,
        journeys: List<JourneyDebugStep>,
        values: Map<String, Any?>? = null,
        includeWhenEmpty: Boolean = false,
        session: DemoSession? = null
    ): DemoInfo?
}

/**
 * The disclosing implementation, active in demo mode. `persons` and `invitations` ([DemoPersonas])
 * are attached here for every caller, so the pickers work everywhere.
 */
@Component
@OnlyInDemoMode
class DisclosingDemoDisclosure(private val personas: DemoPersonas) : DemoDisclosure {

    override fun assemble(
        accountId: AccountId?,
        personId: PartnerNumber?,
        journeys: List<JourneyDebugStep>,
        values: Map<String, Any?>?,
        includeWhenEmpty: Boolean,
        session: DemoSession?
    ): DemoInfo? {
        if (!includeWhenEmpty && values.isNullOrEmpty() && journeys.isEmpty() && session == null) return null
        return DemoInfo(
            accountId = accountId,
            personId = personId,
            journeys = journeys,
            session = session,
            values = (values ?: emptyMap()) + ("persons" to personas.all()) + ("invitations" to personas.invitations())
        )
    }
}

/** `demo.mode=false`: nothing is disclosed. Not a filter but the absence of a builder. */
@Component
@OutsideDemoMode
class WithheldDemoDisclosure : DemoDisclosure {

    override fun assemble(
        accountId: AccountId?,
        personId: PartnerNumber?,
        journeys: List<JourneyDebugStep>,
        values: Map<String, Any?>?,
        includeWhenEmpty: Boolean,
        session: DemoSession?
    ): DemoInfo? = null
}
