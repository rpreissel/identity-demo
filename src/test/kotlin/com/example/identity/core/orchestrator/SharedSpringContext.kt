package com.example.identity.core.orchestrator

import com.example.identity.core.orchestrator.dpop.DpopValidator
import com.example.identity.core.orchestrator.dpop.JwkThumbprintService
import com.example.identity.core.orchestrator.keycloak.PeerAuthValidator
import com.example.identity.core.orchestrator.session.TokenProvider
import com.ninjasquad.springmockk.MockkSpyBean
import io.kotest.core.spec.style.BehaviorSpec
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles

/**
 * The one Spring context the specs share. Spring starts a context per configuration, so a spec
 * declares no beans of its own: every bean a spec fakes is a spy here. Unstubbed calls run the real
 * code; a spec stubs only what it fakes, and the stubs are cleared after each test.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@Import(PinnedToolCatalogTestConfig::class)
abstract class SharedSpringContext(body: BehaviorSpec.() -> Unit = {}) : BehaviorSpec(body) {

    @MockkSpyBean
    protected lateinit var dpopValidator: DpopValidator

    @MockkSpyBean
    protected lateinit var jwkThumbprintService: JwkThumbprintService

    @MockkSpyBean
    protected lateinit var peerAuthValidator: PeerAuthValidator

    @MockkSpyBean
    protected lateinit var tokenProvider: TokenProvider
}
