package com.example.identity.core.orchestrator.domain.journey.strategy

import com.example.identity.core.orchestrator.domain.journey.IntentStrategy

/** One strategy per intent, as `DomainBeans` registers them, for tests that run without Spring. */
val ALL_INTENT_STRATEGIES: List<IntentStrategy<*>> = listOf(
    FastAccessStrategy(), RegisterDispatchStrategy(), LookupLoginStrategy(), WebSelectMethodStrategy(),
    StepUpStrategy(), ManageAuthMethodsStrategy(), ConfirmPeerLoginStrategy(), DeleteAccountStrategy(),
    FreshProofStrategy(), ReIdentifyStrategy(),
)
