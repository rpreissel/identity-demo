package com.example.identity.simulation.kobil.api.v1

import com.example.identity.demo.demo_mode.DemoSurface
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.http.HttpHeaders
import com.example.identity.contract.texts.TextBundle
import com.example.identity.contract.texts.Text
import com.example.identity.simulation.kobil.KobilRejectedException
import com.example.identity.simulation.kobil.KobilRisk
import com.example.identity.simulation.kobil.KobilSsms
import com.example.identity.simulation.kobil.KobilUserRef
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class KobilActivateRequest(val tenantId: String, val userId: String, val activationCode: String, val pin: String)

data class KobilLoginRequest(val tenantId: String, val userId: String, val pin: String)

data class KobilOtpResponse(val otp: String)

// risks as List, not Set, like ActiveMethodView.factorTypes: a Set becomes a JS Set in the client.
data class KobilRiskSimulationRequest(val tenantId: String, val userId: String, val risks: List<KobilRisk>)

/**
 * The face the app talks to, standing in for what the MC SDK does on a phone. Not under
 * `/orchestrator/api`, because the device speaks to KOBIL directly; that is what the flow
 * demonstrates. No DPoP, no channel, no journey.
 */
@RestController
@DemoSurface
@RequestMapping("/mock-kobil")
@Tag(name = "Mock KOBIL", description = "Simuliertes KOBIL-Backend - kein Endpunkt dieser Anwendung, sondern der Fremddienst")
class KobilMockController(private val ssms: KobilSsms) {

    @PostMapping("activate")
    @Operation(
        summary = "MC SDK ActivateEvent",
        description = "Binds this device to the user and creates the device identifier. The " +
            "activation code is spent in the process."
    )
    fun activate(@RequestBody request: KobilActivateRequest): Map<String, String> {
        val deviceId = ssms.activate(
            KobilUserRef(request.tenantId, request.userId),
            request.activationCode,
            request.pin,
        )
        return mapOf("deviceId" to deviceId)
    }

    @PostMapping("login")
    @Operation(
        summary = "MC SDK LoginEvent",
        description = "Checks the device, files an assertion, and returns only the one-time " +
            "password that points at it. The assertion itself never reaches the caller."
    )
    fun login(@RequestBody request: KobilLoginRequest): KobilOtpResponse =
        KobilOtpResponse(ssms.login(KobilUserRef(request.tenantId, request.userId), request.pin))

    @PostMapping("simulate-risk")
    @Operation(
        summary = "Demo switch: what this device's sensors report from now on",
        description = "Has no counterpart in the real product. Without it the risk-rejection path " +
            "would only ever be reachable from a test."
    )
    fun simulateRisk(@RequestBody request: KobilRiskSimulationRequest): Map<String, Any> {
        ssms.simulateRiskSignals(KobilUserRef(request.tenantId, request.userId), request.risks.toSet())
        return mapOf("risks" to request.risks.map { it.name })
    }

    /** KOBIL answers for itself; its refusals are not this application's error contract. */
    @ExceptionHandler(KobilRejectedException::class)
    fun rejected(exception: KobilRejectedException): ResponseEntity<Map<String, Text>> =
        ResponseEntity.status(HttpStatus.BAD_REQUEST).body(mapOf("error" to exception.text))

    /**
     * This service's own texts in [lang], for its own page - a foreign system brings its wordings
     * along (docs/adr/ADR-033). ETag/If-None-Match: 304 while the client's copy is current.
     */
    @GetMapping("texts/{lang}")
    @Operation(summary = "Texte des Dienstes in einer Sprache (mit ETag)")
    fun texts(
        @PathVariable lang: String,
        @RequestHeader(HttpHeaders.IF_NONE_MATCH, required = false) ifNoneMatch: String?
    ): ResponseEntity<Map<String, String>> = TEXTS.respond(lang, ifNoneMatch)

    private companion object {
        val TEXTS = TextBundle("kobil")
    }
}
